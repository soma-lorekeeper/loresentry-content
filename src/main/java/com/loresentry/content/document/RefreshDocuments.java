package com.loresentry.content.document;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 그래프 최신화(refresh 패키지)가 문서를 읽고 쓰는 유일한 창구.
 *
 * <p>문서 테이블·본문 규칙은 이 패키지가 갖는다. 최신화가 SQL 을 직접 쓰면 저장 규칙(본문 검증, 관계 한 쌍 한 행,
 * 휴지통 제외)이 두 곳에 생기고 언젠가 어긋난다. DB 를 건드리는 메서드는 모두 호출자의 트랜잭션 안에서만 돈다
 * ({@code MANDATORY}) — 최신화는 run 상태 변경과 문서 변경을 한 트랜잭션으로 묶는다.
 */
@Component
public class RefreshDocuments {

    private final DocumentRepository repository;

    private final DocumentService documents;

    public RefreshDocuments(DocumentRepository repository, DocumentService documents) {
        this.repository = repository;
        this.documents = documents;
    }

    /**
     * 문서의 지금 상태.
     *
     * @param userLocked 사용자가 건 잠금. 최신화 잠금은 포함하지 않는다
     * @param snapshot   제목·본문·속성·관계. 관계는 휴지통 대상을 뺀, 편집 화면과 같은 목록이다
     */
    public record Live(UUID id, String folderCode, long revisionNo, boolean userLocked, boolean trashed,
            DocumentSnapshot snapshot) {
    }

    /** 이 프로젝트 문서 중 {@code ids} 에 있는 것. 없는 id 는 결과에 없다. 휴지통 문서는 {@code trashed} 로 알린다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Live> read(UUID projectId, Collection<UUID> ids) {
        Map<UUID, Live> live = new LinkedHashMap<>();
        for (DocumentRepository.LiveRow row : repository.liveRows(projectId, ids)) {
            live.put(row.id(), new Live(row.id(), row.folderCode(), row.revisionNo(), row.userLocked(),
                    row.trashed(), row.snapshot()));
        }
        return live;
    }

    /**
     * 최신화 기준 버전(REFRESH_BASE)을 남긴다. 사용자 버전 목록에는 보이지 않는다. 지우는 것은 최신화가 끝날 때다
     * ({@link #deleteRefreshBases}) — 만료 시각을 두지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID insertRefreshBase(UUID fileId, long revisionNo, DocumentSnapshot snapshot) {
        return repository.insertVersion(fileId, revisionNo, "REFRESH_BASE", null, snapshot, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, DocumentSnapshot> refreshBases(Collection<UUID> versionIds) {
        return repository.refreshBases(versionIds);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteRefreshBases(Collection<UUID> versionIds) {
        repository.deleteRefreshBases(versionIds);
    }

    /** 최신화 run 이 문서들을 잠근다. 잠긴 문서는 편집·이름 변경·이동·휴지통·잠금 API 가 막힌다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID runId, Collection<UUID> ids) {
        repository.lockForRefresh(runId, ids);
    }

    /** run 이 끝날 때(확정·변경 없음·실패·시간 초과) 그 run 이 건 잠금을 푼다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void unlock(UUID runId) {
        repository.unlockRefresh(runId);
    }

    /** 분류 코드 → 관계 키. 작업본의 관계 키를 서버가 다시 정할 때 쓴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, String> relationKeysByFolder() {
        return repository.relationKeysByFolder();
    }

    /** graph-rag 의 평문 본문을 에디터 본문으로 되돌린다. 규칙은 {@link BodyFromText}. */
    public JsonNode bodyFromText(String text, JsonNode referenceBody) {
        return BodyFromText.fromText(text, referenceBody);
    }

    /** 문서 저장과 같은 본문 검사. 통과하면 그대로 돌려주고, 아니면 {@code INVALID_REQUEST} 로 실패한다. */
    public JsonNode requireValidBody(JsonNode body) {
        return documents.validateBody(body);
    }
}
