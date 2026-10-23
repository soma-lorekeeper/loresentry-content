package com.loresentry.content.refresh;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
public class RefreshDraftRepository {

    private final JdbcClient jdbcClient;

    private final JsonMapper json = JsonMapper.builder().build();

    RefreshDraftRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 요청 때 실은 문서의 작업본. left 와 그 원본(origin)은 실은 그 상태이고, 기준 버전이 같은 상태를 보관한다.
     * right 는 결과가 와야 생긴다.
     */
    public void insertBaseDraft(UUID runId, UUID documentId, UUID baseVersionId, long revisionNo, DraftSnapshot left) {
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
    public record BaseDraft(UUID id, UUID targetDocumentId, UUID baseVersionId, long leftRevisionNo, DraftSnapshot left) {
    }

    /** 이 run 이 요청 때 만든 작업본들(BASE_CAPTURED). 대상 문서로 찾는다. */
    public Map<UUID, BaseDraft> baseDrafts(UUID runId) {
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
    public void open(UUID draftId, DraftSnapshot right, boolean baseMismatch) {
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
    public void insertOpenEdit(UUID runId, UUID documentId, long leftRevisionNo, DraftSnapshot left, DraftSnapshot right,
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
    public void insertNew(UUID runId, String candidateId, String folderCode, DraftSnapshot right) {
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
    public List<UUID> deleteUnopened(UUID runId) {
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

    /** 열린 작업본 수, 그중 맞춘 수, 그중 새 문서 제안 수. */
    public DraftCounts counts(UUID runId) {
        return jdbcClient
                .sql("""
                        select count(*) as total,
                               count(*) filter (where matched) as matched,
                               count(*) filter (where kind = 'NEW') as created
                        from refresh_document_drafts
                        where refresh_run_id = :run and status = 'OPEN'
                        """)
                .param("run", runId)
                .query((rows, index) -> new DraftCounts(rows.getLong("total"), rows.getLong("matched"),
                        rows.getLong("created")))
                .single();
    }

    /**
     * 열린 작업본 하나와 화면에 필요한 주변 정보(대상 문서의 지금 제목·분류·revision, 기준 버전의 revision).
     * 대상 문서 칸은 새 문서 작업본이면 비어 있다.
     */
    public record OpenDraft(UUID id, String kind, long draftRevision, boolean matched, OffsetDateTime updatedAt,
            UUID targetDocumentId, String documentTitle, String documentFolder, Long documentRevision,
            String candidateId, String folderCode, UUID baseVersionId, Long baseRevisionNo, boolean baseMismatch,
            Long leftRevisionNo, DraftSnapshot left, DraftSnapshot right) {
    }

    /** 열린 작업본들. 기존 문서 먼저, 분류 순서와 제목 순이고, 새 문서 제안은 뒤에 임시 이름 순으로 온다. */
    public List<OpenDraft> openDrafts(UUID runId) {
        return jdbcClient
                .sql("""
                        select r.id, r.kind, r.draft_revision, r.matched, r.updated_at, r.target_document_id,
                               d.title as document_title, f.code as document_folder, d.revision_no as document_revision,
                               r.candidate_id, r.folder_code, r.base_version_id, v.source_revision_no as base_revision,
                               r.base_mismatch, r.left_source_revision_no, r.left_snapshot::text as left_json,
                               r.right_snapshot::text as right_json
                        from refresh_document_drafts r
                        left join document d on d.id = r.target_document_id
                        left join base_folders f on f.id = d.folder_id
                        left join document_versions v on v.id = r.base_version_id
                        where r.refresh_run_id = :run and r.status = 'OPEN'
                        order by (r.kind = 'NEW'), f.position nulls last, d.title, r.candidate_id
                        """)
                .param("run", runId)
                .query((rows, index) -> new OpenDraft(rows.getObject("id", UUID.class), rows.getString("kind"),
                        rows.getLong("draft_revision"), rows.getBoolean("matched"),
                        rows.getObject("updated_at", OffsetDateTime.class),
                        rows.getObject("target_document_id", UUID.class), rows.getString("document_title"),
                        rows.getString("document_folder"), rows.getObject("document_revision", Long.class),
                        rows.getString("candidate_id"), rows.getString("folder_code"),
                        rows.getObject("base_version_id", UUID.class), rows.getObject("base_revision", Long.class),
                        rows.getBoolean("base_mismatch"), rows.getObject("left_source_revision_no", Long.class),
                        snapshotOrNull(rows.getString("left_json")), snapshotOrNull(rows.getString("right_json"))))
                .list();
    }

    /** 확정이 읽는 작업본 하나. origin 은 처음 뜬 left 다(관계 차이를 구하는 기준). */
    public record ConfirmDraft(UUID id, String kind, UUID targetDocumentId, String candidateId, String folderCode,
            Long leftRevisionNo, DraftSnapshot origin, DraftSnapshot right, boolean matched) {
    }

    /** 이 run 의 열린 작업본 전부. 확정은 run 을 잠근 뒤 읽으므로 그 사이 저장이 끼어들지 않는다. */
    public List<ConfirmDraft> openForConfirm(UUID runId) {
        return jdbcClient
                .sql("""
                        select id, kind, target_document_id, candidate_id, folder_code, left_source_revision_no,
                               origin_snapshot::text as origin_json, right_snapshot::text as right_json, matched
                        from refresh_document_drafts
                        where refresh_run_id = :run and status = 'OPEN'
                        order by kind, target_document_id, candidate_id
                        """)
                .param("run", runId)
                .query((rows, index) -> new ConfirmDraft(rows.getObject("id", UUID.class), rows.getString("kind"),
                        rows.getObject("target_document_id", UUID.class), rows.getString("candidate_id"),
                        rows.getString("folder_code"), rows.getObject("left_source_revision_no", Long.class),
                        snapshotOrNull(rows.getString("origin_json")), snapshotOrNull(rows.getString("right_json")),
                        rows.getBoolean("matched")))
                .list();
    }

    /** 저장할 작업본의 종류와 대상. 이 run 의 열린 작업본이 아니면 비어 있다. */
    public record DraftHead(UUID id, String kind, UUID targetDocumentId, String candidateId) {
    }

    public Optional<DraftHead> findOpen(UUID runId, UUID draftId) {
        return jdbcClient
                .sql("""
                        select id, kind, target_document_id, candidate_id from refresh_document_drafts
                        where id = :id and refresh_run_id = :run and status = 'OPEN'
                        """)
                .param("id", draftId).param("run", runId)
                .query((rows, index) -> new DraftHead(rows.getObject("id", UUID.class), rows.getString("kind"),
                        rows.getObject("target_document_id", UUID.class), rows.getString("candidate_id")))
                .optional();
    }

    /** 이 run 의 새 설정 문서 제안: 임시 이름 → 분류. 작업본의 관계가 아직 없는 문서를 가리킬 때 확인한다. */
    public Map<String, String> candidateFolders(UUID runId) {
        Map<String, String> folders = new LinkedHashMap<>();
        jdbcClient
                .sql("select candidate_id, folder_code from refresh_document_drafts "
                        + "where refresh_run_id = :run and kind = 'NEW' order by candidate_id")
                .param("run", runId)
                .query(rows -> {
                    folders.put(rows.getString("candidate_id"), rows.getString("folder_code"));
                });
        return folders;
    }

    /** 작업본의 지금 모습. 자동 저장이 충돌했을 때 클라이언트에 돌려준다. */
    public record Current(long draftRevision, boolean matched, OffsetDateTime updatedAt, DraftSnapshot left,
            DraftSnapshot right) {
    }

    /**
     * 두 쪽을 저장한다. 읽은 뒤 다른 탭·기기가 먼저 저장했으면({@code draft_revision} 이 다르면) 저장하지 않고 비어 있다
     * (설계 §7.5). 저장하면 번호가 하나 오르고, matched 는 DB 가 다시 계산한다.
     */
    public Optional<Current> save(UUID draftId, long expectedRevision, DraftSnapshot left, DraftSnapshot right) {
        return jdbcClient
                .sql("""
                        update refresh_document_drafts
                        set left_snapshot = :left::jsonb, right_snapshot = :right::jsonb,
                            draft_revision = draft_revision + 1, updated_at = now()
                        where id = :id and draft_revision = :expected
                        returning draft_revision, matched, updated_at, left_snapshot::text as left_json,
                                  right_snapshot::text as right_json
                        """)
                .param("id", draftId)
                .param("expected", expectedRevision)
                .param("left", left == null ? null : json.writeValueAsString(left))
                .param("right", right == null ? null : json.writeValueAsString(right))
                .query(this::mapCurrent)
                .optional();
    }

    public Current current(UUID draftId) {
        return jdbcClient
                .sql("""
                        select draft_revision, matched, updated_at, left_snapshot::text as left_json,
                               right_snapshot::text as right_json
                        from refresh_document_drafts where id = :id
                        """)
                .param("id", draftId)
                .query(this::mapCurrent)
                .single();
    }

    private Current mapCurrent(ResultSet rows, int index) throws SQLException {
        return new Current(rows.getLong("draft_revision"), rows.getBoolean("matched"),
                rows.getObject("updated_at", OffsetDateTime.class), snapshotOrNull(rows.getString("left_json")),
                snapshotOrNull(rows.getString("right_json")));
    }

    private DraftSnapshot snapshotOrNull(String text) {
        return text == null ? null : json.readValue(text, DraftSnapshot.class);
    }

    /** 기준 버전이 없는 작업본(이웃 문서·새 문서)은 null 을 돌려주므로 뺀다. */
    private static List<UUID> baseVersionIds(List<UUID> ids) {
        return ids.stream().filter(Objects::nonNull).toList();
    }
}
