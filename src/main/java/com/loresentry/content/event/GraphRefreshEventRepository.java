package com.loresentry.content.event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.event.RefreshInput.Document;
import com.loresentry.content.event.RefreshInput.Property;
import com.loresentry.content.event.RefreshInput.Relation;
import com.loresentry.content.manuscript.Manuscripts;
import com.loresentry.content.manuscript.Purpose;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 그래프 최신화 메시지를 채울 값을 읽는다. 문서는 여러 개를 한 번에 읽는다 — 문서·속성·관계마다 쿼리 하나씩이다.
 *
 * <p>모두 {@code MANDATORY} 다. 최신화 요청 트랜잭션이 고른 문서와 같은 시점의 상태를 읽어야, S3 에 실은 revision
 * 과 기록한 revision 이 어긋나지 않는다.
 */
@Repository
class GraphRefreshEventRepository {

    private final JdbcClient jdbcClient;

    private final Manuscripts manuscripts;

    GraphRefreshEventRepository(JdbcClient jdbcClient, Manuscripts manuscripts) {
        this.jdbcClient = jdbcClient;
        this.manuscripts = manuscripts;
    }

    /**
     * 이 프로젝트의 활성 문서 중 {@code ids} 에 있는 것을 id 순으로 읽는다. 휴지통이거나 없는 id 는 결과에 없다.
     *
     * <p>관계는 편집 화면({@code DocumentRepository.relations})과 같은 규칙이다 — 행의 어느 쪽에 있든 상대가
     * 대상이고, 키는 상대의 분류가 정하며, 휴지통에 있는 상대는 뺀다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Document> documents(UUID projectId, List<UUID> ids) {
        Map<UUID, List<Property>> properties = new HashMap<>();
        jdbcClient
                .sql("select document_id, property_key, text_value from document_properties "
                        + "where document_id in (:ids) order by document_id, position, property_key")
                .param("ids", ids)
                .query(rows -> {
                    properties.computeIfAbsent(rows.getObject("document_id", UUID.class), id -> new ArrayList<>())
                            .add(new Property(rows.getString("property_key"), rows.getString("text_value")));
                });

        Map<UUID, List<Relation>> relations = new HashMap<>();
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
                            .add(new Relation(rows.getObject("target_document_id", UUID.class),
                                    rows.getString("relation_key"), rows.getString("description")));
                });

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
                    return new Document(id, rows.getLong("revision_no"), rows.getString("folder_code"),
                            rows.getString("title"),
                            manuscripts.open(projectId, id, Purpose.BODY_TEXT, rows.getBytes("body_text_enc")),
                            properties.getOrDefault(id, List.of()), relations.getOrDefault(id, List.of()));
                })
                .list();
    }

    /**
     * 이 프로젝트의 문서마다 가장 최근 FileChanged 이벤트의 id. 지워진 문서의 마지막(DELETED) 이벤트도 들어간다.
     *
     * <p>outbox 에서 이미 정리된 이벤트(발행 뒤 3일)는 없다. 그만큼 오래된 이벤트는 graph-rag 가 이미 반영했다고 본다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<UUID> latestFileChangedEvents(UUID projectId) {
        return jdbcClient
                .sql("""
                        select distinct on (aggregate_id) id
                        from outbox_events
                        where event_type = :type and event_key = :project
                        order by aggregate_id, seq desc
                        """)
                .param("type", ProjectChangedMessages.FILE_CHANGED)
                .param("project", projectId.toString())
                .query(UUID.class)
                .list();
    }
}
