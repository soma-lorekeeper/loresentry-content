package com.loresentry.content.refresh;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검토 작업본({@code refresh_document_drafts}). 작업본의 생애:
 *
 * <ol>
 *   <li>요청 때 S3 에 실은 문서마다 left 만 채워 만든다(BASE_CAPTURED).
 *   <li>결과가 오면 제안이 있는 작업본에 right 를 채우고(OPEN), 제안이 없는 것은 지운다. graph-rag 가 더 읽은 문서나
 *       새 설정 문서 제안은 이때 새로 만든다.
 *   <li>검토 중에는 사용자가 left·right 를 고친다. 두 쪽이 같아지면 matched 다.
 *   <li>run 이 끝나면(확정·변경 없음·실패·시간 초과) 모두 지운다.
 * </ol>
 */
@Repository
class RefreshDraftRepository {

    private final JdbcClient jdbcClient;

    private final JsonMapper json = JsonMapper.builder().build();

    RefreshDraftRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 요청 때 실은 문서의 작업본. left 와 그 원본(origin)은 실은 그 상태이고, 기준 버전이 같은 상태를 보관한다.
     * right 는 결과가 와야 생긴다.
     */
    void insertBaseDraft(UUID runId, UUID documentId, UUID baseVersionId, long revisionNo, DraftSnapshot left) {
        String leftJson = json.writeValueAsString(left);
        jdbcClient
                .sql("""
                        insert into refresh_document_drafts
                            (refresh_run_id, kind, target_document_id, base_version_id, left_source_revision_no,
                             left_snapshot, origin_snapshot, status)
                        values (:run, 'EDIT', :document, :base, :revision, :left::jsonb, :left::jsonb,
                                'BASE_CAPTURED')
                        """)
                .param("run", runId)
                .param("document", documentId)
                .param("base", baseVersionId)
                .param("revision", revisionNo)
                .param("left", leftJson)
                .update();
    }

    /** 요청 때 만든, 아직 결과를 받지 않은 작업본 하나. */
    record BaseDraft(UUID id, UUID targetDocumentId, UUID baseVersionId, long leftRevisionNo, DraftSnapshot left) {
    }

    /** 이 run 이 요청 때 만든 작업본들(BASE_CAPTURED). 대상 문서로 찾는다. */
    Map<UUID, BaseDraft> baseDrafts(UUID runId) {
        Map<UUID, BaseDraft> drafts = new LinkedHashMap<>();
        jdbcClient
                .sql("""
                        select id, target_document_id, base_version_id, left_source_revision_no, left_snapshot::text
                        from refresh_document_drafts
                        where refresh_run_id = :run and status = 'BASE_CAPTURED'
                        order by target_document_id
                        """)
                .param("run", runId)
                .query(rows -> {
                    UUID target = rows.getObject("target_document_id", UUID.class);
                    drafts.put(target, new BaseDraft(rows.getObject("id", UUID.class), target,
                            rows.getObject("base_version_id", UUID.class), rows.getLong("left_source_revision_no"),
                            json.readValue(rows.getString("left_snapshot"), DraftSnapshot.class)));
                });
        return drafts;
    }

    /** 요청 때 만든 작업본에 제안(right)을 채워 검토를 연다. */
    void open(UUID draftId, DraftSnapshot right, boolean baseMismatch) {
        jdbcClient
                .sql("""
                        update refresh_document_drafts
                        set status = 'OPEN', right_snapshot = :right::jsonb, base_mismatch = :mismatch,
                            updated_at = now()
                        where id = :id
                        """)
                .param("right", json.writeValueAsString(right))
                .param("mismatch", baseMismatch)
                .param("id", draftId)
                .update();
    }

    /**
     * 요청 때 싣지 않은 문서(graph-rag 가 목록 API 로 더 읽은 문서)의 작업본. left 는 지금 문서이고 기준 버전은 없다.
     *
     * @param baseMismatch graph-rag 가 본 revision 이 지금 revision 과 다르다
     */
    void insertOpenEdit(UUID runId, UUID documentId, long leftRevisionNo, DraftSnapshot left, DraftSnapshot right,
            boolean baseMismatch) {
        String leftJson = json.writeValueAsString(left);
        jdbcClient
                .sql("""
                        insert into refresh_document_drafts
                            (refresh_run_id, kind, target_document_id, left_source_revision_no, left_snapshot,
                             origin_snapshot, right_snapshot, base_mismatch, status)
                        values (:run, 'EDIT', :document, :revision, :left::jsonb, :left::jsonb, :right::jsonb,
                                :mismatch, 'OPEN')
                        """)
                .param("run", runId)
                .param("document", documentId)
                .param("revision", leftRevisionNo)
                .param("left", leftJson)
                .param("right", json.writeValueAsString(right))
                .param("mismatch", baseMismatch)
                .update();
    }

    /** 새 설정 문서 제안의 작업본. 아직 문서가 없으므로 left 는 비어 있다. 사용자가 받아들이면 left 를 right 와 같게 한다. */
    void insertNew(UUID runId, String candidateId, String folderCode, DraftSnapshot right) {
        jdbcClient
                .sql("""
                        insert into refresh_document_drafts
                            (refresh_run_id, kind, candidate_id, folder_code, right_snapshot, status)
                        values (:run, 'NEW', :candidate, :folder, :right::jsonb, 'OPEN')
                        """)
                .param("run", runId)
                .param("candidate", candidateId)
                .param("folder", folderCode)
                .param("right", json.writeValueAsString(right))
                .update();
    }

    /** 결과에 제안이 없어 열리지 않은 작업본을 지우고, 그 작업본들의 기준 버전 id 를 돌려준다. */
    List<UUID> deleteUnopened(UUID runId) {
        return baseVersionIds(jdbcClient
                .sql("""
                        delete from refresh_document_drafts
                        where refresh_run_id = :run and status = 'BASE_CAPTURED'
                        returning base_version_id
                        """)
                .param("run", runId)
                .query((rows, index) -> rows.getObject("base_version_id", UUID.class))
                .list());
    }

    /** run 의 작업본을 모두 지우고, 기준 버전 id 들을 돌려준다. run 이 끝날 때 쓴다. */
    List<UUID> deleteByRun(UUID runId) {
        return baseVersionIds(jdbcClient
                .sql("delete from refresh_document_drafts where refresh_run_id = :run returning base_version_id")
                .param("run", runId)
                .query((rows, index) -> rows.getObject("base_version_id", UUID.class))
                .list());
    }

    /** 기준 버전이 없는 작업본(이웃 문서·새 문서)은 null 을 돌려주므로 뺀다. */
    private static List<UUID> baseVersionIds(List<UUID> ids) {
        return ids.stream().filter(Objects::nonNull).toList();
    }
}
