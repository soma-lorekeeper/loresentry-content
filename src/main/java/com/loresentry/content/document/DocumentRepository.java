package com.loresentry.content.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
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
                row.id(), row.projectId(), row.title(), row.folderCode(), row.episodeId(), row.bodyMd(),
                properties(row.id()), relations(row.id()), row.locked(), row.charCount(), row.revisionNo(),
                row.updatedAt());
    }

    /** 휴지통 여부와 마지막 저장 id 는 응답에 없지만 서비스가 판단에 쓴다. */
    record Header(UUID id, UUID projectId, String title, String folderCode, UUID episodeId, String bodyMd,
            boolean locked, int charCount, long revisionNo, OffsetDateTime updatedAt,
            OffsetDateTime trashedAt, UUID lastSaveId) {
    }

    Optional<Header> findHeader(UUID ownerUserId, UUID fileId) {
        return jdbcClient
                .sql("""
                        select d.id, d.project_id, d.title, f.code as folder_code, d.episode_id,
                               d.body_md, d.locked, d.char_count, d.revision_no, d.updated_at,
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
                        rows.getString("body_md"),
                        rows.getBoolean("locked"),
                        rows.getInt("char_count"),
                        rows.getLong("revision_no"),
                        rows.getObject("updated_at", OffsetDateTime.class),
                        rows.getObject("trashed_at", OffsetDateTime.class),
                        rows.getObject("last_save_id", UUID.class)))
                .optional();
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

    List<DocumentSnapshot.Relation> relations(UUID fileId) {
        return jdbcClient
                .sql("select relation_key, target_document_id from document_relations "
                        + "where document_id = :id order by position, relation_key")
                .param("id", fileId)
                .query((rows, index) -> new DocumentSnapshot.Relation(
                        rows.getString("relation_key"), rows.getObject("target_document_id", UUID.class)))
                .list();
    }

    /**
     * 조건부 저장이다. 0행이 바뀌면 다른 탭이 먼저 저장한 것이므로, 오래된 본문으로 최신 문서를
     * 덮어쓰지 않고 호출자에게 알린다(TABLE_AND_LOGIC §7.2).
     */
    boolean updateIfRevisionMatches(UUID fileId, long expectedRevision, DocumentSnapshot snapshot,
            UUID saveId) {
        int updated = jdbcClient
                .sql("""
                        update document
                        set title = :title,
                            body_md = :body,
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
                .param("body", snapshot.bodyMd())
                .param("chars", snapshot.bodyMd().length())
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

    void replaceRelations(UUID fileId, List<DocumentSnapshot.Relation> relations) {
        jdbcClient.sql("delete from document_relations where document_id = :id")
                .param("id", fileId).update();
        int position = 10;
        for (DocumentSnapshot.Relation relation : relations) {
            jdbcClient
                    .sql("""
                            insert into document_relations
                                (document_id, relation_key, target_document_id, position)
                            values (:id, :key, :target, :position)
                            """)
                    .param("id", fileId).param("key", relation.relationKey())
                    .param("target", relation.targetDocumentId()).param("position", position)
                    .update();
            position += 10;
        }
    }

    /**
     * 반대쪽 문서에 역방향 행을 하나 더한다. 이미 있으면 그대로 둔다.
     *
     * <p>대상 문서의 {@code revision_no}는 올리지 않는다. 관계는 본문이 아니고, 올리면 그 문서를
     * 열어 둔 편집기가 저장할 때마다 충돌로 떨어진다.
     */
    void addRelation(UUID documentId, String relationKey, UUID targetId) {
        jdbcClient
                .sql("""
                        insert into document_relations
                            (document_id, relation_key, target_document_id, position)
                        select :id, :key, :target,
                               coalesce((select max(position) from document_relations
                                         where document_id = :id), 0) + 10
                        on conflict (document_id, relation_key, target_document_id) do nothing
                        """)
                .param("id", documentId).param("key", relationKey).param("target", targetId)
                .update();
    }

    void removeRelation(UUID documentId, String relationKey, UUID targetId) {
        jdbcClient
                .sql("delete from document_relations where document_id = :id"
                        + " and relation_key = :key and target_document_id = :target")
                .param("id", documentId).param("key", relationKey).param("target", targetId)
                .update();
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
