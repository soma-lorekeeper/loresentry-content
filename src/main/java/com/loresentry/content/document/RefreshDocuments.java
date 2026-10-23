package com.loresentry.content.document;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** 확정 직전의 문서 상태. */
    public record Locked(UUID id, long revisionNo, boolean userLocked, UUID refreshLockRunId, boolean trashed) {
    }

    /** 확정이 쓸 문서들을 id 순으로 잠그고 읽는다. 없는(영구 삭제된) 문서는 결과에 없다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Locked> lockForApply(Collection<UUID> ids) {
        Map<UUID, Locked> locked = new LinkedHashMap<>();
        for (DocumentRepository.ApplyRow row : repository.lockForApply(ids)) {
            locked.put(row.id(), new Locked(row.id(), row.revisionNo(), row.userLocked(), row.refreshLockRunId(),
                    row.trashed()));
        }
        return locked;
    }

    /** 확정이 실제로 바꾼 것. 이벤트의 바뀐 칸을 고르는 데 쓴다. */
    public record Written(boolean titleChanged, boolean bodyChanged, boolean propertiesChanged) {
    }

    /**
     * 문서의 제목·본문·속성을 쓴다. 문서 저장과 같은 규칙이다(본문 평문·글자 수를 다시 만들고, revision 을 올린다).
     * 관계는 여기서 쓰지 않는다 — 확정은 관계를 쌍 단위로 더하고 뺀다({@link #putRelation}).
     *
     * <p>호출자가 문서를 잠그고 revision 을 확인한 뒤 부른다. 그래도 어긋나면 이미 다른 저장이 끼어든 것이므로 실패한다.
     * 같은 자리에 같은 제목이 있으면 {@code DuplicateKeyException} 이 나간다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Written write(UUID fileId, long expectedRevision, String title, JsonNode body,
            List<DocumentSnapshot.TextProperty> properties) {
        DocumentSnapshot snapshot = new DocumentSnapshot(title, body, null, properties, List.of());
        DocumentRepository.Updated updated = repository
                .updateIfRevisionMatches(fileId, expectedRevision, snapshot, BodyText.extract(body.get("doc")), null)
                .orElseThrow(() -> new IllegalStateException("Locked document changed before the refresh wrote it"));
        boolean propertiesChanged = !repository.properties(fileId).equals(properties);
        repository.replaceProperties(fileId, properties);
        return new Written(updated.titleChanged(), updated.bodyChanged(), propertiesChanged);
    }

    /**
     * 관계 한 쌍을 만들거나 설명을 바꾼다.
     *
     * @return 바뀌었으면 그 관계를 가진 문서(low). 관계 이벤트는 그 문서의 것이다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> putRelation(UUID one, UUID other, String description) {
        return repository.upsertRelation(one, other, description).map(DocumentRepository.RelationPair::low);
    }

    /** 관계 한 쌍을 지운다. 지웠으면 그 관계를 가졌던 문서(low)를 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> removeRelation(UUID one, UUID other) {
        return repository.deleteRelation(one, other).map(DocumentRepository.RelationPair::low);
    }

    /**
     * 확정이 바꾼 문서의 지금 상태를 AI_APPLY 버전으로 남긴다. 사용자 버전 목록에 보이고, 이름 붙인 버전처럼 만료가
     * 없다 — "AI 제안을 반영한 시점"으로 되돌아올 수 있게 한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAiApply(UUID projectId, UUID fileId) {
        DocumentRepository.LiveRow row = repository.liveRows(projectId, List.of(fileId)).getFirst();
        repository.insertVersion(fileId, row.revisionNo(), "AI_APPLY", null, row.snapshot(), null);
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
