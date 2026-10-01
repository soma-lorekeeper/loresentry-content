package com.loresentry.content.document;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DocumentRepository {

    private final JdbcClient jdbcClient;

    private final JsonMapper json = JsonMapper.builder().build();

    public DocumentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Optional<DocumentResponses.Content> find(UUID ownerUserId, UUID fileId) {
        return findHeader(ownerUserId, fileId).map(this::contentOf);
    }

    DocumentResponses.Content contentOf(Header row) {
        return new DocumentResponses.Content(
                row.id(), row.projectId(), row.title(), row.folderCode(), row.episodeId(),
                row.body(), row.legacyBodyMd(),
                properties(row.id()), relations(row.id()), row.locked(), row.charCount(), row.revisionNo(),
                row.updatedAt());
    }

    /**
     * 휴지통 여부와 마지막 저장 id 는 응답에 없지만 서비스가 판단에 쓴다.
     *
     * <p>{@code body} 와 {@code legacyBodyMd} 는 둘 중 하나만 채워진다. 아직 변환되지 않은 행은
     * {@code body_json} 이 비어 있고 Markdown 만 있다.
     */
    record Header(UUID id, UUID projectId, String title, String folderCode, UUID episodeId,
            JsonNode body, String legacyBodyMd,
            boolean locked, int charCount, long revisionNo, OffsetDateTime updatedAt,
            OffsetDateTime trashedAt, UUID lastSaveId) {
    }

    Optional<Header> findHeader(UUID ownerUserId, UUID fileId) {
        return jdbcClient
                .sql("""
                        select d.id, d.project_id, d.title, f.code as folder_code, d.episode_id,
                               d.body_json, d.body_md, d.locked, d.char_count, d.revision_no, d.updated_at,
                               d.trashed_at, d.last_save_id
                        from document d
                        join base_folders f on f.id = d.folder_id
                        join projects p on p.id = d.project_id
                        where d.id = :id and p.owner_user_id = :owner
                        """)
                .param("id", fileId)
                .param("owner", ownerUserId)
                .query((rows, index) -> new Header(
                        rows.getObject("id", UUID.class),
                        rows.getObject("project_id", UUID.class),
                        rows.getString("title"),
                        rows.getString("folder_code"),
                        rows.getObject("episode_id", UUID.class),
                        readBody(rows.getString("body_json")),
                        rows.getString("body_json") == null ? rows.getString("body_md") : null,
                        rows.getBoolean("locked"),
                        rows.getInt("char_count"),
                        rows.getLong("revision_no"),
                        rows.getObject("updated_at", OffsetDateTime.class),
                        rows.getObject("trashed_at", OffsetDateTime.class),
                        rows.getObject("last_save_id", UUID.class)))
                .optional();
    }

    private JsonNode readBody(String bodyJson) {
        return bodyJson == null ? null : json.readTree(bodyJson);
    }

    List<DocumentSnapshot.TextProperty> properties(UUID fileId) {
        return jdbcClient
                .sql("select property_key, text_value from document_properties "
                        + "where document_id = :id order by position, property_key")
                .param("id", fileId)
                .query((rows, index) -> new DocumentSnapshot.TextProperty(
                        rows.getString("property_key"), rows.getString("text_value")))
                .list();
    }

    /**
     * 한 쌍에 한 행이므로, 이 문서는 행의 어느 쪽에나 있을 수 있다. 반대쪽 끝이 곧 대상이고,
     * 관계 키는 <b>그 반대쪽 문서의 분류</b>가 정한다 — 원고에서 캐릭터를 보면
     * {@code related_character}, 같은 행을 캐릭터에서 보면 {@code related_manuscript}다.
     *
     * <p>휴지통에 있는 문서로 가는 행은 내놓지 않는다. 화면이 열 수 없는 칩을 그리게 되고,
     * 저장할 때 그 대상을 돌려보내면 {@link #isUsableRelationTarget}이 막아 저장 자체가 실패한다.
     * 행은 그대로 두므로 그 문서를 되살리면 관계도 함께 돌아온다.
     */
    List<DocumentSnapshot.Relation> relations(UUID fileId) {
        return jdbcClient
                .sql("""
                        select f.relation_key, other.id as target_document_id, r.description
                        from document_relations r
                        join document other
                          on other.id = case when r.low_document_id = :id
                                             then r.high_document_id else r.low_document_id end
                        join base_folders f on f.id = other.folder_id
                        where (r.low_document_id = :id or r.high_document_id = :id)
                          and other.trashed_at is null
                        order by f.relation_key, r.id
                        """)
                .param("id", fileId)
                .query((rows, index) -> new DocumentSnapshot.Relation(
                        rows.getString("relation_key"),
                        rows.getObject("target_document_id", UUID.class),
                        rows.getString("description")))
                .list();
    }

    /**
     * 조건부 저장이다. 0행이 바뀌면 다른 탭이 먼저 저장한 것이므로, 오래된 본문으로 최신 문서를
     * 덮어쓰지 않고 호출자에게 알린다(TABLE_AND_LOGIC §7.2).
     */
    boolean updateIfRevisionMatches(UUID fileId, long expectedRevision, DocumentSnapshot snapshot,
            String bodyText, UUID saveId) {
        int updated = jdbcClient
                .sql("""
                        update document
                        set title = :title,
                            body_json = :body::jsonb,
                            body_text = :text,
                            body_sha = encode(sha256(convert_to(:body, 'UTF8')), 'hex'),
                            char_count = :chars,
                            revision_no = revision_no + 1,
                            last_save_id = :saveId,
                            updated_at = now()
                        where id = :id and revision_no = :expected
                        """)
                .param("id", fileId)
                .param("expected", expectedRevision)
                .param("title", snapshot.title())
                // body_md 에는 쓰지 않는다. 레거시 행을 읽는 용도로만 남겨 둔 칸이다.
                .param("body", json.writeValueAsString(snapshot.body()))
                .param("text", bodyText)
                .param("chars", BodyText.charCount(bodyText))
                .param("saveId", saveId)
                .update();
        return updated == 1;
    }

    void replaceProperties(UUID fileId, List<DocumentSnapshot.TextProperty> properties) {
        jdbcClient.sql("delete from document_properties where document_id = :id")
                .param("id", fileId).update();
        int position = 10;
        for (DocumentSnapshot.TextProperty property : properties) {
            jdbcClient
                    .sql("insert into document_properties (document_id, property_key, text_value, position) "
                            + "values (:id, :key, :value, :position)")
                    .param("id", fileId).param("key", property.key())
                    .param("value", property.value()).param("position", position)
                    .update();
            position += 10;
        }
    }

    /**
     * 이 문서의 관계를 들어온 목록과 똑같이 맞춘다.
     *
     * <p>행을 전부 지우고 다시 넣지 않는다. 한 행이 두 문서의 것이므로, 지웠다가 넣는 사이에 설명이
     * 사라지고 행 id 가 바뀐다. 사라진 것만 지우고 남은 것은 설명만 맞춘다.
     *
     * <p><b>휴지통 문서와의 관계는 건드리지 않는다.</b> 그 행은 읽을 때 빠지므로 들어온 목록에도
     * 없고, 그래서 "지워진 것"으로 보인다. 지우면 그 문서를 되살려도 관계가 돌아오지 않는다.
     *
     * <p>들어온 {@code relation_key}는 쓰지 않는다. 키는 대상 문서의 분류에서 나오므로 저장할 것이
     * 없고, 저장하면 분류를 바꿨을 때 어긋난다.
     */
    void replaceRelations(UUID fileId, List<DocumentSnapshot.Relation> relations) {
        // 같은 대상을 여러 키로 가리켜도 쌍은 하나다. 설명이 적힌 쪽을 남긴다.
        Map<UUID, String> wanted = new LinkedHashMap<>();
        for (DocumentSnapshot.Relation relation : relations) {
            UUID target = relation.targetDocumentId();
            if (target == null || target.equals(fileId)) {
                continue;
            }
            String existing = wanted.get(target);
            if (existing == null || existing.isEmpty()) {
                wanted.put(target, relation.descriptionOrEmpty());
            }
        }

        jdbcClient
                .sql("""
                        delete from document_relations r
                        using document other
                        where (r.low_document_id = :id or r.high_document_id = :id)
                          and other.id = case when r.low_document_id = :id
                                              then r.high_document_id else r.low_document_id end
                          and other.trashed_at is null
                          and other.id <> all (:keep)
                        """)
                .param("id", fileId)
                .param("keep", wanted.keySet().toArray(UUID[]::new))
                .update();

        for (Map.Entry<UUID, String> entry : wanted.entrySet()) {
            jdbcClient
                    .sql("""
                            insert into document_relations
                                (low_document_id, high_document_id, description)
                            values (least(:id, :target), greatest(:id, :target), :description)
                            on conflict (low_document_id, high_document_id)
                            do update set description = excluded.description
                            """)
                    .param("id", fileId)
                    .param("target", entry.getKey())
                    .param("description", entry.getValue())
                    .update();
        }
    }

    /** 관계 대상은 같은 프로젝트의 활성 문서여야 한다. 외래 키는 존재만 보장하고 프로젝트는 보지 않는다. */
    boolean isUsableRelationTarget(UUID projectId, UUID targetId) {
        return jdbcClient
                .sql("select 1 from document where id = :id and project_id = :project"
                        + " and trashed_at is null")
                .param("id", targetId).param("project", projectId)
                .query(Integer.class).optional().isPresent();
    }

    void setLocked(UUID fileId, boolean locked) {
        jdbcClient.sql("update document set locked = :locked, updated_at = now() where id = :id")
                .param("id", fileId).param("locked", locked).update();
    }

    UUID insertVersion(UUID fileId, long sourceRevision, String kind, String label,
            DocumentSnapshot snapshot, OffsetDateTime expiresAt) {
        return jdbcClient
                .sql("""
                        insert into document_versions
                            (document_id, source_revision_no, kind, label, snapshot, expires_at)
                        values (:id, :revision, :kind, :label, :snapshot::jsonb, :expires)
                        returning id
                        """)
                .param("id", fileId)
                .param("revision", sourceRevision)
                .param("kind", kind)
                .param("label", label)
                .param("snapshot", json.writeValueAsString(snapshot))
                .param("expires", expiresAt)
                .query(UUID.class)
                .single();
    }

    /** 최신화의 내부 기준({@code REFRESH_BASE})은 사용자 버전 목록에 넣지 않는다(TABLE_AND_LOGIC §4.8). */
    List<DocumentResponses.Version> versions(UUID fileId) {
        return jdbcClient
                .sql("""
                        select id, document_id, kind, label, source_revision_no, created_at, snapshot
                        from document_versions
                        where document_id = :id and kind <> 'REFRESH_BASE'
                        order by created_at desc
                        """)
                .param("id", fileId)
                .query((rows, index) -> mapVersion(rows))
                .list();
    }

    Optional<DocumentResponses.Version> findVersion(UUID fileId, UUID versionId) {
        return jdbcClient
                .sql("""
                        select id, document_id, kind, label, source_revision_no, created_at, snapshot
                        from document_versions
                        where id = :version and document_id = :id and kind <> 'REFRESH_BASE'
                        """)
                .param("version", versionId).param("id", fileId)
                .query((rows, index) -> mapVersion(rows))
                .optional();
    }

    /** 충돌 응답의 공통 조상. 그 revision 의 스냅샷이 남아 있지 않으면 비어 있다. */
    Optional<DocumentSnapshot> snapshotAtRevision(UUID fileId, long revisionNo) {
        return jdbcClient
                .sql("""
                        select snapshot from document_versions
                        where document_id = :id and source_revision_no = :revision
                        order by created_at desc limit 1
                        """)
                .param("id", fileId).param("revision", revisionNo)
                .query((rows, index) -> json.readValue(rows.getString("snapshot"), DocumentSnapshot.class))
                .optional();
    }

    Optional<OffsetDateTime> lastAutoVersionAt(UUID fileId) {
        return jdbcClient
                .sql("select max(created_at) from document_versions "
                        + "where document_id = :id and kind = 'AUTO'")
                .param("id", fileId)
                .query(OffsetDateTime.class)
                .optional();
    }

    void deleteVersion(UUID versionId) {
        jdbcClient.sql("delete from document_versions where id = :id").param("id", versionId).update();
    }

    private DocumentResponses.Version mapVersion(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new DocumentResponses.Version(
                rows.getObject("id", UUID.class),
                rows.getObject("document_id", UUID.class),
                rows.getString("kind"),
                rows.getString("label"),
                rows.getLong("source_revision_no"),
                rows.getObject("created_at", OffsetDateTime.class),
                json.readValue(rows.getString("snapshot"), DocumentSnapshot.class));
    }
}
