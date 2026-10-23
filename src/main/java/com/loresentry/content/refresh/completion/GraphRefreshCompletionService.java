package com.loresentry.content.refresh.completion;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import com.loresentry.content.refresh.GraphRefreshRepository;
import com.loresentry.content.refresh.RefreshCleanup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;

/**
 * 그래프 최신화 완료.
 *
 * <p>graph-rag 가 끝났다고(성공이든 실패든) 보낸 완료 이벤트를 두 단계로 처리한다.
 * <ul>
 *   <li>{@link #fetchResult}: DB 트랜잭션 밖에서 결과 파일을 가져온다.
 *   <li>{@link #complete}: inbox 처리 기록과 같은 트랜잭션에서 run 을 끝내거나 검토 작업본을 연다.
 * </ul>
 */
@Service
public class GraphRefreshCompletionService {

    private final GraphRefreshRepository repository;

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

    GraphRefreshCompletionService(GraphRefreshRepository repository, RefreshResultStorage results,
            RefreshDraftBuilder builder, RefreshCleanup cleanup,
            @Value("${graph-refresh.completed-bucket:}") String completedBucket,
            @Value("${graph-refresh.result-max-bytes:16777216}") long resultMaxBytes) {
        this.repository = repository;
        this.results = results;
        this.builder = builder;
        this.cleanup = cleanup;
        this.completedBucket = completedBucket;
        this.resultMaxBytes = resultMaxBytes;
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
