package com.loresentry.content.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.loresentry.content.manuscript.Manuscripts;
import com.loresentry.content.manuscript.Purpose;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DocumentRepository {

    private final JdbcClient jdbcClient;

    private final Manuscripts manuscripts;

    private final JsonMapper json = JsonMapper.builder().build();

    public DocumentRepository(JdbcClient jdbcClient, Manuscripts manuscripts) {
        this.jdbcClient = jdbcClient;
        this.manuscripts = manuscripts;
    }

    Optional<DocumentResponses.Content> find(UUID ownerUserId, UUID fileId) {
        return findHeader(ownerUserId, fileId).map(this::contentOf);
    }

    DocumentResponses.Content contentOf(Header row) {
        return new DocumentResponses.Content(
                row.id(), row.projectId(), row.title(), row.folderCode(), row.episodeId(),
                row.body(), row.legacyBodyMd(),
                properties(row.id()), relations(row.id()), row.locked(), row.refreshLocked(), row.charCount(),
                row.revisionNo(), row.updatedAt());
    }

    /**
     * 휴지통 여부와 마지막 저장 id 는 응답에 없지만 서비스가 판단에 쓴다.
     *
     * <p>{@code body} 와 {@code legacyBodyMd} 는 둘 중 하나만 채워진다. 아직 변환되지 않은 행은
     * {@code body_json} 이 비어 있고 Markdown 만 있다.
     */
    /**
     * {@code locked} 는 편집할 수 있는지(사용자 잠금 또는 최신화 잠금), {@code refreshLocked} 는 그중 최신화 잠금이
     * 있는지다. 저장·복원은 {@code locked} 로 막고, 잠금 API 는 {@code refreshLocked} 면 거부한다.
     */
    record Header(UUID id, UUID projectId, String title, String folderCode, UUID episodeId,
            JsonNode body, String legacyBodyMd,
            boolean locked, boolean refreshLocked, int charCount, long revisionNo, OffsetDateTime updatedAt,
            OffsetDateTime trashedAt, UUID lastSaveId) {
    }

    Optional<Header> findHeader(UUID ownerUserId, UUID fileId) {
        return jdbcClient
                .sql("""
                        select d.id, d.project_id, d.title, f.code as folder_code, d.episode_id,
                               d.body_json_enc, d.body_md_enc,
                               (d.locked or d.refresh_lock_run_id is not null) as locked,
                               d.refresh_lock_run_id is not null as refresh_locked,
                               d.char_count, d.revision_no, d.updated_at,
                               d.trashed_at, d.last_save_id
                        from document d
                        join base_folders f on f.id = d.folder_id
                        join projects p on p.id = d.project_id
                        where d.id = :id and p.owner_user_id = :owner
                        """)
                .param("id", fileId)
                .param("owner", ownerUserId)
                .query((rows, index) -> {
                    UUID id = rows.getObject("id", UUID.class);
                    UUID projectId = rows.getObject("project_id", UUID.class);
                    String bodyJson = manuscripts.open(projectId, id, Purpose.BODY_JSON, rows.getBytes("body_json_enc"));
                    return new Header(
                            id,
                            projectId,
                            rows.getString("title"),
                            rows.getString("folder_code"),
                            rows.getObject("episode_id", UUID.class),
                            readBody(bodyJson),
                            bodyJson == null
                                    ? manuscripts.open(projectId, id, Purpose.BODY_MD, rows.getBytes("body_md_enc"))
                                    : null,
                            rows.getBoolean("locked"),
                            rows.getBoolean("refresh_locked"),
                            rows.getInt("char_count"),
                            rows.getLong("revision_no"),
                            rows.getObject("updated_at", OffsetDateTime.class),
                            rows.getObject("trashed_at", OffsetDateTime.class),
                            rows.getObject("last_save_id", UUID.class));
                })
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

    /** 휴지통에 있는 프로젝트의 문서는 읽지 않는다. 남의 프로젝트와 같이 없는 것으로 답한다. */
    boolean ownsActiveProject(UUID ownerUserId, UUID projectId) {
        return jdbcClient
                .sql("select 1 from projects where id = :id and owner_user_id = :owner and trashed_at is null")
                .param("id", projectId)
                .param("owner", ownerUserId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * 이 프로젝트의 활성 문서 중 {@code ids} 에 있는 것을 id 순으로 읽는다. 휴지통이거나 없는 id 는 결과에 없다.
     * 문서마다 따로 묻지 않고 문서·속성·관계를 각각 한 쿼리로 읽는다.
     *
     * <p>속성 순서와 관계 규칙은 {@link #properties}·{@link #relations} 와 같다.
     */
    List<DocumentResponses.ContentItem> contents(UUID projectId, List<UUID> ids) {
        Map<UUID, List<DocumentSnapshot.TextProperty>> properties = propertiesOf(ids);
        Map<UUID, List<DocumentSnapshot.Relation>> relations = relationsOf(ids);

        return jdbcClient
                .sql("""
                        select d.id, d.revision_no, f.code as folder_code, d.title, d.body_text_enc
                        from document d
                        join base_folders f on f.id = d.folder_id
                        where d.project_id = :project and d.id in (:ids) and d.trashed_at is null
                        order by d.id
                        """)
                .param("project", projectId)
                .param("ids", ids)
                .query((rows, index) -> {
                    UUID id = rows.getObject("id", UUID.class);
                    return new DocumentResponses.ContentItem(id, rows.getLong("revision_no"),
                            rows.getString("folder_code"), rows.getString("title"),
                            manuscripts.open(projectId, id, Purpose.BODY_TEXT, rows.getBytes("body_text_enc")),
                            properties.getOrDefault(id, List.of()), relations.getOrDefault(id, List.of()));
                })
                .list();
    }

    /** 여러 문서의 속성을 한 쿼리로 읽는다. 문서별 순서는 {@link #properties} 와 같다. */
    private Map<UUID, List<DocumentSnapshot.TextProperty>> propertiesOf(Collection<UUID> ids) {
        Map<UUID, List<DocumentSnapshot.TextProperty>> properties = new HashMap<>();
        jdbcClient
                .sql("select document_id, property_key, text_value from document_properties "
                        + "where document_id in (:ids) order by document_id, position, property_key")
                .param("ids", ids)
                .query(rows -> {
                    properties.computeIfAbsent(rows.getObject("document_id", UUID.class), id -> new ArrayList<>())
                            .add(new DocumentSnapshot.TextProperty(
                                    rows.getString("property_key"), rows.getString("text_value")));
                });
        return properties;
    }

    /** 여러 문서의 관계를 한 쿼리로 읽는다. 규칙(휴지통 대상 제외, 키는 대상 분류)은 {@link #relations} 와 같다. */
    private Map<UUID, List<DocumentSnapshot.Relation>> relationsOf(Collection<UUID> ids) {
        Map<UUID, List<DocumentSnapshot.Relation>> relations = new HashMap<>();
        jdbcClient
                .sql("""
                        select r.self_id, f.relation_key, other.id as target_document_id, r.description
                        from (
                            -- 한 쌍이 한 행이라, 두 끝 모두 이 문서가 될 수 있다. 양쪽에서 본 모양으로 펼친다.
                            select id, low_document_id as self_id, high_document_id as other_id, description
                            from document_relations where low_document_id in (:ids)
                            union all
                            select id, high_document_id, low_document_id, description
                            from document_relations where high_document_id in (:ids)
                        ) r
                        join document other on other.id = r.other_id
                        join base_folders f on f.id = other.folder_id
                        where other.trashed_at is null
                        order by r.self_id, f.relation_key, r.id
                        """)
                .param("ids", ids)
                .query(rows -> {
                    relations.computeIfAbsent(rows.getObject("self_id", UUID.class), id -> new ArrayList<>())
                            .add(new DocumentSnapshot.Relation(rows.getString("relation_key"),
                                    rows.getObject("target_document_id", UUID.class), rows.getString("description")));
                });
        return relations;
    }

    /**
     * 최신화가 읽는 문서의 지금 상태. {@code userLocked} 는 사용자가 건 잠금만이다(최신화 잠금은 따로 본다).
     * 휴지통 문서도 읽는다 — 최신화는 그 문서를 건너뛸지 스스로 정한다.
     */
    record LiveRow(UUID id, String folderCode, long revisionNo, boolean userLocked, UUID refreshLockRunId,
            boolean trashed, DocumentSnapshot snapshot) {
    }

    /** 이 프로젝트의 문서 중 {@code ids} 에 있는 것을 읽는다(없는 id 는 빠진다). 문서·속성·관계를 각각 한 쿼리로 읽는다. */
    List<LiveRow> liveRows(UUID projectId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<DocumentSnapshot.TextProperty>> properties = propertiesOf(ids);
        Map<UUID, List<DocumentSnapshot.Relation>> relations = relationsOf(ids);
        return jdbcClient
                .sql("""
                        select d.id, f.code as folder_code, d.revision_no, d.title, d.body_json_enc, d.body_md_enc,
                               d.locked, d.refresh_lock_run_id, d.trashed_at
                        from document d
                        join base_folders f on f.id = d.folder_id
                        where d.project_id = :project and d.id in (:ids)
                        order by d.id
                        """)
                .param("project", projectId)
                .param("ids", ids)
                .query((rows, index) -> {
                    UUID id = rows.getObject("id", UUID.class);
                    String bodyJson = manuscripts.open(projectId, id, Purpose.BODY_JSON, rows.getBytes("body_json_enc"));
                    DocumentSnapshot snapshot = new DocumentSnapshot(rows.getString("title"), readBody(bodyJson),
                            bodyJson == null
                                    ? manuscripts.open(projectId, id, Purpose.BODY_MD, rows.getBytes("body_md_enc"))
                                    : null,
                            properties.getOrDefault(id, List.of()), relations.getOrDefault(id, List.of()));
                    return new LiveRow(id, rows.getString("folder_code"), rows.getLong("revision_no"),
                            rows.getBoolean("locked"), rows.getObject("refresh_lock_run_id", UUID.class),
                            rows.getObject("trashed_at", OffsetDateTime.class) != null, snapshot);
                })
                .list();
    }

    /** 확정 직전의 문서 상태. {@code userLocked} 는 사용자 잠금만이다. */
    record ApplyRow(UUID id, long revisionNo, boolean userLocked, UUID refreshLockRunId, boolean trashed) {
    }

    /**
     * 확정이 쓸 문서들을 잠그고 읽는다. id 순으로 잠가 동시 확정끼리 교착하지 않게 한다. {@code for no key update} 다 —
     * {@code for update} 는 관계 행을 넣을 때 외래 키 검사가 잡는 key share 잠금과 부딪혀 저장과 교착할 수 있다.
     */
    List<ApplyRow> lockForApply(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbcClient
                .sql("""
                        select id, revision_no, locked, refresh_lock_run_id, trashed_at
                        from document where id in (:ids) order by id for no key update
                        """)
                .param("ids", ids)
                .query((rows, index) -> new ApplyRow(rows.getObject("id", UUID.class), rows.getLong("revision_no"),
                        rows.getBoolean("locked"), rows.getObject("refresh_lock_run_id", UUID.class),
                        rows.getObject("trashed_at", OffsetDateTime.class) != null))
                .list();
    }

    /**
     * 관계 한 쌍을 만들거나 설명을 맞춘다. 이미 같은 설명이면 아무것도 하지 않는다. 규칙은 {@link #replaceRelations}
     * 의 넣기와 같다(한 쌍 한 행, low 가 작은 id).
     *
     * @return 실제로 바뀐 쌍. 그대로면 비어 있다
     */
    Optional<RelationPair> upsertRelation(UUID one, UUID other, String description) {
        return jdbcClient
                .sql("""
                        insert into document_relations (low_document_id, high_document_id, description)
                        values (least(:one, :other), greatest(:one, :other), :description)
                        on conflict (low_document_id, high_document_id)
                        do update set description = excluded.description
                        where document_relations.description is distinct from excluded.description
                        returning low_document_id, high_document_id
                        """)
                .param("one", one).param("other", other).param("description", description)
                .query(DocumentRepository::mapPair)
                .optional();
    }

    /** 관계 한 쌍을 지운다. 없으면 비어 있다. */
    Optional<RelationPair> deleteRelation(UUID one, UUID other) {
        return jdbcClient
                .sql("""
                        delete from document_relations
                        where low_document_id = least(:one, :other) and high_document_id = greatest(:one, :other)
                        returning low_document_id, high_document_id
                        """)
                .param("one", one).param("other", other)
                .query(DocumentRepository::mapPair)
                .optional();
    }

    /** 최신화 run 이 이 문서들을 잠근다. 편집 가능 여부는 사용자 잠금과 합쳐 본다(V16). */
    void lockForRefresh(UUID runId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbcClient.sql("update document set refresh_lock_run_id = :run where id in (:ids)")
                .param("run", runId).param("ids", ids).update();
    }

    /** 이 run 이 건 잠금만 푼다. 사용자 잠금은 그대로 남는다. */
    void unlockRefresh(UUID runId) {
        jdbcClient.sql("update document set refresh_lock_run_id = null where refresh_lock_run_id = :run")
                .param("run", runId).update();
    }

    /** 최신화 기준 버전들의 스냅샷. 없는 id 는 빠진다. */
    Map<UUID, DocumentSnapshot> refreshBases(Collection<UUID> versionIds) {
        Map<UUID, DocumentSnapshot> snapshots = new HashMap<>();
        if (versionIds.isEmpty()) {
            return snapshots;
        }
        jdbcClient
                .sql("""
                        select v.id, v.document_id, d.project_id, v.snapshot_enc
                        from document_versions v join document d on d.id = v.document_id
                        where v.id in (:ids) and v.kind = 'REFRESH_BASE'
                        """)
                .param("ids", versionIds)
                .query(rows -> {
                    snapshots.put(rows.getObject("id", UUID.class), versionSnapshot(rows));
                });
        return snapshots;
    }

    /** 최신화 기준 버전을 지운다. 사용자 버전(AUTO·NAMED 등)은 kind 로 걸러 건드리지 않는다. */
    void deleteRefreshBases(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return;
        }
        jdbcClient.sql("delete from document_versions where id in (:ids) and kind = 'REFRESH_BASE'")
                .param("ids", versionIds).update();
    }

    /** 분류 코드 → 그 분류 문서를 가리키는 관계 키(V10). 관계 키는 대상 문서의 분류에서 나온다. */
    Map<String, String> relationKeysByFolder() {
        Map<String, String> keys = new HashMap<>();
        jdbcClient.sql("select code, relation_key from base_folders")
                .query(rows -> {
                    keys.put(rows.getString("code"), rows.getString("relation_key"));
                });
        return keys;
    }

    /**
     * 저장이 실제로 바꾼 것. 저장 전에 따로 읽어 비교하지 않고 update 문이 돌려준 전후 값으로 정한다 — 따로 읽으면
     * 읽은 뒤 update 전에 다른 트랜잭션(이름 변경은 revision 을 올리지 않는다)이 끼어들 수 있다.
     *
     * @param bodyChanged 본문 평문({@code body_text})이 바뀌었나. 서식만 바뀐 저장은 false 다
     */
    record Updated(boolean titleChanged, boolean bodyChanged) {
    }

    /** 바뀐 관계 한 쌍. 행의 low·high 그대로다. */
    record RelationPair(UUID low, UUID high) {
    }

    /**
     * 조건부 저장이다. 0행이 바뀌면 다른 탭이 먼저 저장한 것이므로, 오래된 본문으로 최신 문서를
     * 덮어쓰지 않고 호출자에게 알린다(TABLE_AND_LOGIC §7.2). 그때 결과가 비어 있다.
     */
    Optional<Updated> updateIfRevisionMatches(UUID fileId, long expectedRevision, DocumentSnapshot snapshot,
            String bodyText, UUID saveId) {
        Optional<UUID> project = projectOf(fileId);
        if (project.isEmpty()) {
            return Optional.empty();
        }
        UUID projectId = project.get();
        String body = json.writeValueAsString(snapshot.body());
        return jdbcClient
                .sql("""
                        update document
                        set title = :title,
                            body_json_enc = :bodyEnc,
                            body_md_enc = null,
                            body_text_enc = :textEnc,
                            body_text_mac = :textMac,
                            body_text_length = :textLength,
                            char_count = :chars,
                            revision_no = revision_no + 1,
                            last_save_id = :saveId,
                            updated_at = now()
                        where id = :id and revision_no = :expected
                        returning old.title is distinct from new.title as title_changed,
                                  old.body_text_mac is distinct from new.body_text_mac as body_changed
                        """)
                .param("id", fileId)
                .param("expected", expectedRevision)
                .param("title", snapshot.title())
                .param("bodyEnc", manuscripts.seal(projectId, fileId, Purpose.BODY_JSON, body))
                .param("textEnc", manuscripts.seal(projectId, fileId, Purpose.BODY_TEXT, bodyText))
                .param("textMac", manuscripts.mac(projectId, bodyText))
                .param("textLength", bodyText.codePointCount(0, bodyText.length()))
                .param("chars", BodyText.charCount(bodyText))
                .param("saveId", saveId)
                .query((rows, index) -> new Updated(
                        rows.getBoolean("title_changed"), rows.getBoolean("body_changed")))
                .optional();
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
     *
     * @return 실제로 지워지거나, 새로 생기거나, 설명이 바뀐 쌍. 각 SQL 이 돌려준 행이라 저장 전후를 따로 읽는 것과
     *         달리 그 사이에 끼어든 다른 저장에 속지 않는다. 그 관계를 가진 문서(low)의 이벤트를 고르는 데 쓴다
     */
    List<RelationPair> replaceRelations(UUID fileId, List<DocumentSnapshot.Relation> relations) {
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

        List<RelationPair> changed = new ArrayList<>(jdbcClient
                .sql("""
                        delete from document_relations r
                        using document other
                        where (r.low_document_id = :id or r.high_document_id = :id)
                          and other.id = case when r.low_document_id = :id
                                              then r.high_document_id else r.low_document_id end
                          and other.trashed_at is null
                          and other.id <> all (:keep)
                        returning r.low_document_id, r.high_document_id
                        """)
                .param("id", fileId)
                .param("keep", wanted.keySet().toArray(UUID[]::new))
                .query(DocumentRepository::mapPair)
                .list());

        for (Map.Entry<UUID, String> entry : wanted.entrySet()) {
            // 설명이 같으면 갱신하지 않는다. 그래야 returning 이 행을 내놓지 않아 "바뀐 것"에서 빠진다.
            jdbcClient
                    .sql("""
                            insert into document_relations
                                (low_document_id, high_document_id, description)
                            values (least(:id, :target), greatest(:id, :target), :description)
                            on conflict (low_document_id, high_document_id)
                            do update set description = excluded.description
                            where document_relations.description is distinct from excluded.description
                            returning low_document_id, high_document_id
                            """)
                    .param("id", fileId)
                    .param("target", entry.getKey())
                    .param("description", entry.getValue())
                    .query(DocumentRepository::mapPair)
                    .optional()
                    .ifPresent(changed::add);
        }
        return changed;
    }

    private static RelationPair mapPair(ResultSet rows, int index) throws SQLException {
        return new RelationPair(
                rows.getObject("low_document_id", UUID.class), rows.getObject("high_document_id", UUID.class));
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
        UUID projectId = projectOf(fileId).orElseThrow();
        String snapshotJson = json.writeValueAsString(snapshot);
        return jdbcClient
                .sql("""
                        insert into document_versions
                            (document_id, source_revision_no, kind, label, snapshot_enc, expires_at)
                        values (:id, :revision, :kind, :label, :snapshotEnc, :expires)
                        returning id
                        """)
                .param("id", fileId)
                .param("revision", sourceRevision)
                .param("kind", kind)
                .param("label", label)
                .param("snapshotEnc", manuscripts.seal(projectId, fileId, Purpose.VERSION_SNAPSHOT, snapshotJson))
                .param("expires", expiresAt)
                .query(UUID.class)
                .single();
    }

    /** 최신화의 내부 기준({@code REFRESH_BASE})은 사용자 버전 목록에 넣지 않는다(TABLE_AND_LOGIC §4.8). */
    List<DocumentResponses.Version> versions(UUID fileId) {
        return jdbcClient
                .sql("""
                        select v.id, v.document_id, d.project_id, v.kind, v.label, v.source_revision_no,
                               v.created_at, v.snapshot_enc
                        from document_versions v join document d on d.id = v.document_id
                        where v.document_id = :id and v.kind <> 'REFRESH_BASE'
                        order by v.created_at desc
                        """)
                .param("id", fileId)
                .query((rows, index) -> mapVersion(rows))
                .list();
    }

    Optional<DocumentResponses.Version> findVersion(UUID fileId, UUID versionId) {
        return jdbcClient
                .sql("""
                        select v.id, v.document_id, d.project_id, v.kind, v.label, v.source_revision_no,
                               v.created_at, v.snapshot_enc
                        from document_versions v join document d on d.id = v.document_id
                        where v.id = :version and v.document_id = :id and v.kind <> 'REFRESH_BASE'
                        """)
                .param("version", versionId).param("id", fileId)
                .query((rows, index) -> mapVersion(rows))
                .optional();
    }

    /** 충돌 응답의 공통 조상. 그 revision 의 스냅샷이 남아 있지 않으면 비어 있다. */
    Optional<DocumentSnapshot> snapshotAtRevision(UUID fileId, long revisionNo) {
        return jdbcClient
                .sql("""
                        select v.document_id, d.project_id, v.snapshot_enc
                        from document_versions v join document d on d.id = v.document_id
                        where v.document_id = :id and v.source_revision_no = :revision
                        order by v.created_at desc limit 1
                        """)
                .param("id", fileId).param("revision", revisionNo)
                .query((rows, index) -> versionSnapshot(rows))
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
                versionSnapshot(rows));
    }

    private DocumentSnapshot versionSnapshot(ResultSet rows) throws SQLException {
        return json.readValue(manuscripts.open(rows.getObject("project_id", UUID.class),
                rows.getObject("document_id", UUID.class), Purpose.VERSION_SNAPSHOT, rows.getBytes("snapshot_enc")),
                DocumentSnapshot.class);
    }

    private Optional<UUID> projectOf(UUID fileId) {
        return jdbcClient.sql("select project_id from document where id = :id")
                .param("id", fileId)
                .query(UUID.class)
                .optional();
    }
}
