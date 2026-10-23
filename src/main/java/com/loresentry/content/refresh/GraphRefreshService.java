package com.loresentry.content.refresh;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.document.RefreshDocuments;
import com.loresentry.content.event.GraphRefreshMessages;
import com.loresentry.content.event.GraphRefreshMessages.PreparedInput;
import com.loresentry.content.event.GraphRefreshRequested;
import com.loresentry.content.outbox.OutboxRepository;
import com.loresentry.content.web.ContentFailure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    GraphRefreshService(GraphRefreshRepository repository, RefreshInputStorage storage, GraphRefreshMessages messages,
            OutboxRepository outbox, RefreshDocuments documents, RefreshDraftRepository drafts) {
        this.repository = repository;
        this.storage = storage;
        this.messages = messages;
        this.outbox = outbox;
        this.documents = documents;
        this.drafts = drafts;
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
     * 최신화 완료 이벤트 하나를 처리한다.
     *
     * <p>Kafka 에서 받는 쪽(inbox package 의 listener)이 처리 기록과 같은 트랜잭션 안에서 부른다. 그래서
     * {@code MANDATORY} 다 — 이미 열린 트랜잭션에 합류하고, 트랜잭션 없이 불리면 실행하기 전에 예외를 던진다. 처리 기록
     * 없이 처리만 되는 길을 막는다.
     *
     * <p>여기서 하는 DB 쓰기는 같은 thread 에서, 새 트랜잭션을 열지 않고 해야 한다. 다른 thread 로 넘기거나
     * {@code REQUIRES_NEW} 로 새로 열면 그 쓰기는 처리 기록과 따로 확정된다. 결과 파일(S3)을 읽는 것처럼 느린 일은 이
     * 트랜잭션 안에서 하지 않는 편이 좋다 — 그동안 DB connection 을 잡고 있게 된다.
     *
     * @param event 끝난 최신화의 요청 id, 결과 또는 실패 원인
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(GraphRefreshCompletedEvent event) {
        // 다음 task: request_id 의 refresh_runs 행을 outcome 에 따라 READY 나 FAILED 로 바꾸고, 이벤트의 prompt_version 을
        // 적는다. 같은 프로젝트의 결과는 같은 partition 으로 와서 차례로 처리되지만, 상태를 바꿀 때는 지금 상태를 조건으로
        // 건다(예: GENERATING 일 때만). 그때까지 listener 는 kafka.consumer.enabled.graph-refresh-completed 로 꺼 둔다.
        // 켜면 받은 이벤트를 처리한 것으로 기록만 한다.
    }
}
