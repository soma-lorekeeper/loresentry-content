package com.loresentry.content.refresh;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.loresentry.content.document.RefreshDocuments;
import com.loresentry.content.event.GraphRefreshMessages;
import com.loresentry.content.event.GraphRefreshMessages.PreparedInput;
import com.loresentry.content.event.GraphRefreshRequested;
import com.loresentry.content.outbox.OutboxRepository;
import com.loresentry.content.web.ContentFailure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;

/**
 * 그래프 최신화의 요청과 완료.
 *
 * <ul>
 *   <li>{@link #request}: 지난 최신화 이후 바뀌었거나 새로 생긴 활성 문서를 모두 골라 S3 에 올리고, graph-rag 에 처리를
 *       요청하는 이벤트를 outbox 에 남긴다. 이벤트와 S3 입력의 모양은 모른다 — {@link GraphRefreshMessages} 가 만들고,
 *       여기서는 무엇을 보낼지만 정한다.
 *   <li>{@link #complete}: graph-rag 가 끝났다고(성공이든 실패든) 보낸 완료 이벤트를 처리한다.
 * </ul>
 */
@Service
public class GraphRefreshService {

    private final GraphRefreshRepository repository;

    private final RefreshInputStorage storage;

    private final GraphRefreshMessages messages;

    private final OutboxRepository outbox;

    /** 문서를 읽고, 기준 버전을 남기고, 잠그는 창구. 문서 저장 규칙은 문서 패키지가 갖는다. */
    private final RefreshDocuments documents;

    private final RefreshDraftRepository drafts;

    /** graph-rag 결과 파일을 S3 에서 읽는다. */
    private final RefreshResultStorage results;

    /** 결과로 검토 작업본을 연다. */
    private final RefreshDraftBuilder builder;

    /** run 이 끝날 때 작업본·기준 버전·잠금을 치운다. */
    private final RefreshCleanup cleanup;

    /** graph-rag 가 결과를 쓰는 bucket. 설정돼 있으면 완료 이벤트가 다른 bucket 을 가리킬 때 읽지 않는다. */
    private final String completedBucket;

    /** 결과 파일 크기 한도. graph-rag 의 객체 한도와 같다. */
    private final long resultMaxBytes;

    GraphRefreshService(GraphRefreshRepository repository, RefreshInputStorage storage, GraphRefreshMessages messages,
            OutboxRepository outbox, RefreshDocuments documents, RefreshDraftRepository drafts,
            RefreshResultStorage results, RefreshDraftBuilder builder, RefreshCleanup cleanup,
            @Value("${graph-refresh.completed-bucket:}") String completedBucket,
            @Value("${graph-refresh.result-max-bytes:16777216}") long resultMaxBytes) {
        this.repository = repository;
        this.storage = storage;
        this.messages = messages;
        this.outbox = outbox;
        this.documents = documents;
        this.drafts = drafts;
        this.results = results;
        this.builder = builder;
        this.cleanup = cleanup;
        this.completedBucket = completedBucket;
        this.resultMaxBytes = resultMaxBytes;
    }

    /**
     * 최신화 요청 하나.
     *
     * <p>{@code REPEATABLE_READ} 다. 보낼 문서를 고르는 것부터 그 본문·속성·관계, 이벤트의 {@code required_events}
     * 까지 한 시점의 상태에서 읽는다. 그래야 S3 에 실은 revision, 기준선으로 기록한 revision, 이벤트가 말하는 상태가
     * 서로 어긋나지 않는다.
     *
     * <p>run 을 S3 보다 먼저 기록한다. 진행 중인 요청이 있으면 그 자리에서 거절되어, 올리고 버릴 S3 객체가 생기지 않는다.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public GraphRefreshResponse request(UUID userId, UUID projectId) {
        if (!repository.ownsActiveProject(userId, projectId)) {
            throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
        }
        // 검토를 기다리는 결과가 있으면 확정이 먼저다. 결과를 기다리는 중이면 끝나기를 기다린다.
        // (동시에 들어온 요청은 아래 insertRun 의 유일 제약이 막는다.)
        repository.openRunStatus(projectId).ifPresent(status -> {
            throw new ContentFailure("READY".equals(status)
                    ? ContentFailure.Reason.REFRESH_REVIEW_PENDING
                    : ContentFailure.Reason.REFRESH_IN_PROGRESS);
        });
        List<UUID> changed = repository.changedFiles(projectId);
        if (changed.isEmpty()) {
            return GraphRefreshResponse.noChanges();
        }

        UUID requestId;
        try {
            requestId = repository.insertRun(projectId, userId);
        } catch (DuplicateKeyException running) {
            throw new ContentFailure(ContentFailure.Reason.REFRESH_IN_PROGRESS);
        }
        PreparedInput input = messages.input(projectId, changed);
        repository.insertRunFiles(requestId, input.files());
        captureAndLock(projectId, requestId, input.files());
        RefreshInputStorage.Location location = storage.put(projectId, requestId, input.json());

        // outbox 는 마지막에 한 번만 쓴다. append 가 잡는 lock 을 쥔 채 다른 행의 lock 을 기다리지 않게 하려는 것이다.
        outbox.append(messages.requested(projectId, requestId, userId, location.bucket(), location.key(), input));

        return new GraphRefreshResponse(requestId, GraphRefreshResponse.Status.GENERATING, input.files().stream()
                .map(file -> new GraphRefreshResponse.File(file.fileId(), file.revisionNo()))
                .toList());
    }

    /**
     * S3 에 실은 문서마다 기준 버전(REFRESH_BASE)과 left 만 채운 작업본을 남기고, 그 문서들을 이 run 으로 잠근다.
     *
     * <p>같은 REPEATABLE_READ 트랜잭션이라 여기서 읽은 문서는 S3 에 실은 것과 같은 revision 이다. 잠긴 문서는 run 이
     * 끝날 때까지 바뀌지 않으므로, 결과가 왔을 때 left 는 graph-rag 가 본 문서와 같다. S3 에 올리지 못하면 이 모든
     * 기록이 함께 되돌려진다.
     */
    private void captureAndLock(UUID projectId, UUID runId, List<GraphRefreshRequested.File> files) {
        List<UUID> ids = files.stream().map(GraphRefreshRequested.File::fileId).toList();
        Map<UUID, RefreshDocuments.Live> live = documents.read(projectId, ids);
        for (GraphRefreshRequested.File file : files) {
            RefreshDocuments.Live document = live.get(file.fileId());
            UUID baseVersionId = documents.insertRefreshBase(file.fileId(), file.revisionNo(), document.snapshot());
            drafts.insertBaseDraft(runId, file.fileId(), baseVersionId, file.revisionNo(),
                    DraftSnapshots.of(document.snapshot()));
        }
        documents.lock(runId, ids);
    }

    /**
     * 완료 이벤트가 가리키는 결과 파일을 가져온다. DB 트랜잭션 밖에서(listener 가 처리 기록을 열기 전에) 부른다 —
     * S3 를 읽는 동안 DB connection 을 잡지 않는다. 상태는 바꾸지 않는다.
     *
     * <p>run 이 없거나(프로젝트 삭제) 이미 끝났으면(중복·늦은 이벤트) S3 를 읽지 않는다. 결과 파일을 쓸 수 없으면 그
     * 이유를 돌려주고, run 은 반영 때 그 이유로 실패한다. S3 일시 장애는 예외로 나가 Kafka 가 다시 보낸다.
     */
    public ResultFetch fetchResult(GraphRefreshCompletedEvent event) {
        var run = repository.peek(event.requestId());
        if (run.isEmpty() || !run.get().projectId().equals(event.projectId())
                || !"GENERATING".equals(run.get().status())) {
            return new ResultFetch.Skip();
        }
        GraphRefreshCompletedEvent.Result location = event.result();
        if (location == null) {
            return event.outcome() == GraphRefreshCompletedEvent.Outcome.FAILED
                    ? new ResultFetch.NoResult()
                    : new ResultFetch.Unusable("RESULT_MISSING", "Succeeded without a result location");
        }
        if (location.bucket() == null || location.bucket().isBlank() || location.key() == null
                || location.key().isBlank()
                || (!completedBucket.isBlank() && !completedBucket.equals(location.bucket()))) {
            return new ResultFetch.Unusable("RESULT_LOCATION_INVALID", "Result is not in the refresh bucket");
        }

        RefreshResultStorage.Stored stored;
        try {
            stored = results.read(location.bucket(), location.key(), resultMaxBytes);
        } catch (RefreshResultStorage.Unreadable unreadable) {
            return new ResultFetch.Unusable(unreadable.code(), unreadable.getMessage());
        }
        RefreshResult result;
        try {
            result = RefreshResult.parse(stored.bytes());
        } catch (JacksonException malformed) {
            return new ResultFetch.Unusable("RESULT_INVALID", "Result is not valid JSON");
        }
        if (!RefreshResult.SCHEMA_VERSION.equals(result.schemaVersion())
                || !event.requestId().toString().equals(result.requestId())
                || !event.projectId().toString().equals(result.projectId())
                || !OUTCOMES.contains(result.outcome())) {
            return new ResultFetch.Unusable("RESULT_INVALID", "Result does not belong to this request");
        }
        return new ResultFetch.Read(result, new String(stored.bytes(), StandardCharsets.UTF_8), stored.sha256(),
                location.bucket(), location.key());
    }

    /** 결과 파일의 outcome 으로 올 수 있는 값. */
    private static final Set<String> OUTCOMES = Set.of("PROPOSED", "NO_CHANGE", "FAILED");

    /**
     * 최신화 완료 이벤트 하나를 반영한다. 결과 파일은 {@link #fetchResult} 가 미리 가져왔다.
     *
     * <p>Kafka 에서 받는 쪽(inbox package 의 listener)이 처리 기록과 같은 트랜잭션 안에서 부른다. 그래서
     * {@code MANDATORY} 다 — 이미 열린 트랜잭션에 합류하고, 트랜잭션 없이 불리면 실행하기 전에 예외를 던진다. 처리 기록
     * 없이 처리만 되는 길을 막는다.
     *
     * <p>run 을 잠근 뒤 아직 GENERATING 일 때만 바꾼다. 시간 제한으로 이미 실패한 run 에 늦게 온 결과, 같은 결과의 중복,
     * 지워진 프로젝트의 결과는 아무것도 하지 않는다.
     *
     * <ul>
     *   <li>실패 이벤트, 쓸 수 없는 결과, 결과의 FAILED → run 실패. 작업본·기준 버전·잠금을 치운다.
     *   <li>NO_CHANGE, 또는 쓸 수 있는 제안이 하나도 없음 → run 은 "변경 없음"으로 끝나고 같은 정리를 한다.
     *   <li>PROPOSED → 검토 작업본을 열고 READY. 잠금은 확정할 때까지 둔다.
     * </ul>
     * 읽은 결과 파일은 어느 경우든 원본 그대로 남긴다.
     *
     * @param event   끝난 최신화의 요청 id, 결과 위치 또는 실패 원인
     * @param fetched {@link #fetchResult} 가 가져온 것
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(GraphRefreshCompletedEvent event, ResultFetch fetched) {
        var locked = repository.lockRun(event.requestId());
        if (locked.isEmpty() || !locked.get().projectId().equals(event.projectId())
                || !"GENERATING".equals(locked.get().status())) {
            return;
        }
        UUID runId = locked.get().id();
        repository.setPromptVersion(runId, event.promptVersion());
        if (fetched instanceof ResultFetch.Read read) {
            repository.storeResult(runId, read.json(), read.sha256(), read.bucket(), read.key());
        }

        if (event.outcome() == GraphRefreshCompletedEvent.Outcome.FAILED) {
            GraphRefreshCompletedEvent.ErrorDetail error = event.error();
            fail(runId, error == null || error.code() == null ? "GRAPH_RAG_FAILED" : error.code(),
                    error == null ? null : error.message());
            return;
        }
        switch (fetched) {
            case ResultFetch.Unusable unusable -> fail(runId, unusable.code(), unusable.message());
            // 성공 이벤트는 결과 위치 없이 오지 않는다(fetchResult 가 Unusable 로 바꾼다). 미리 본 run 상태와 지금이
            // 다를 수 없으므로 Skip 도 올 수 없지만, 오면 아무것도 바꾸지 않는다.
            case ResultFetch.NoResult none -> fail(runId, "RESULT_MISSING", "Succeeded without a result location");
            case ResultFetch.Skip skip -> {
            }
            case ResultFetch.Read read -> {
                RefreshResult result = read.result();
                switch (result.outcome()) {
                    case "FAILED" -> fail(runId,
                            result.error() == null || result.error().code() == null ? "GRAPH_RAG_FAILED"
                                    : result.error().code(),
                            result.error() == null ? null : result.error().message());
                    case "PROPOSED" -> {
                        if (builder.build(runId, event.projectId(), result)) {
                            repository.markReady(runId);
                        } else {
                            end(runId, "NO_CHANGE");
                        }
                    }
                    default -> end(runId, "NO_CHANGE");
                }
            }
        }
    }

    /** run 을 실패로 끝내고 정리한다. */
    private void fail(UUID runId, String code, String message) {
        cleanup.release(runId);
        repository.markEnded(runId, "FAILED", code, message);
    }

    /** run 을 실패가 아닌 상태로 끝내고 정리한다. */
    private void end(UUID runId, String status) {
        cleanup.release(runId);
        repository.markEnded(runId, status, null, null);
    }
}
