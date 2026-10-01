package com.loresentry.content.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class GraphRepository {

    private final JdbcClient jdbcClient;

    GraphRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    boolean projectExists(UUID ownerUserId, UUID projectId) {
        return jdbcClient
                .sql("select 1 from projects where id = :id and owner_user_id = :owner"
                        + " and trashed_at is null")
                .param("id", projectId).param("owner", ownerUserId)
                .query(Integer.class).optional().isPresent();
    }

    /** 활성 문서 전부. 설명은 {@code description} 속성 한 줄에서 가져온다 — 없으면 빈 문자열이다. */
    List<GraphResponses.Node> nodes(UUID projectId) {
        return jdbcClient
                .sql("""
                        select d.id, d.title, f.code as folder_code,
                               coalesce(p.text_value, '') as description
                        from document d
                        join base_folders f on f.id = d.folder_id
                        left join document_properties p
                            on p.document_id = d.id and p.property_key = 'description'
                        where d.project_id = :project and d.trashed_at is null
                        order by f.position, d.rank, d.id
                        """)
                .param("project", projectId)
                .query((rows, index) -> new GraphResponses.Node(
                        rows.getObject("id", UUID.class),
                        rows.getString("title"),
                        rows.getString("folder_code"),
                        rows.getString("description")))
                .list();
    }

    /**
     * 관계. 한 쌍에 한 행이므로 그대로 내보내면 된다 — 중복을 걸러낼 것이 없다.
     *
     * <p>{@code source}/{@code target}은 두 id 중 작은 쪽을 앞에 둔 저장 순서 그대로다. 관계에는
     * 방향이 없으므로 이 둘은 "어느 쪽에서 이었는가"가 아니라 그저 두 끝이다. 화면이 요청마다 같은
     * 모양을 받으려면 순서가 고정이기만 하면 된다.
     *
     * <p>관계 키는 {@code target} 쪽 문서의 분류가 정한다. 반대쪽에서 본 키는 {@code source} 의
     * 분류가 정하지만, 관계도는 선 하나에 이름 하나만 쓴다.
     *
     * <p>휴지통 문서가 걸린 행은 제외한다.
     */
    List<GraphResponses.Edge> edges(UUID projectId) {
        return jdbcClient
                .sql("""
                        select r.id, r.low_document_id as document_id,
                               r.high_document_id as target_document_id,
                               f.relation_key, r.description
                        from document_relations r
                        join document source on source.id = r.low_document_id
                        join document target on target.id = r.high_document_id
                        join base_folders f on f.id = target.folder_id
                        where source.project_id = :project
                          and source.trashed_at is null and target.trashed_at is null
                        order by r.id
                        """)
                .param("project", projectId)
                .query((rows, index) -> new GraphResponses.Edge(
                        rows.getObject("id", UUID.class),
                        rows.getObject("document_id", UUID.class),
                        rows.getObject("target_document_id", UUID.class),
                        rows.getString("relation_key"),
                        rows.getString("description"),
                        // 지금 저장되는 관계는 모두 사용자가 이은 것이다.
                        "USER"))
                .list();
    }

    /** 회차와 그 안의 원고 순서. 에피소드가 없는 원고는 담기지 않는다 — 화면이 따로 이어 붙인다. */
    List<GraphResponses.Episode> episodes(UUID projectId) {
        record Row(UUID episodeId, String name, UUID documentId) {
        }
        List<Row> rows = jdbcClient
                .sql("""
                        select e.id as episode_id, e.name, d.id as document_id
                        from episode_folders e
                        left join document d
                            on d.episode_id = e.id and d.trashed_at is null
                        where e.project_id = :project
                        order by e.rank, e.id, d.rank, d.id
                        """)
                .param("project", projectId)
                .query((row, index) -> new Row(
                        row.getObject("episode_id", UUID.class),
                        row.getString("name"),
                        row.getObject("document_id", UUID.class)))
                .list();

        Map<UUID, GraphResponses.Episode> byId = new LinkedHashMap<>();
        for (Row row : rows) {
            GraphResponses.Episode episode = byId.computeIfAbsent(row.episodeId(),
                    id -> new GraphResponses.Episode(id, row.name(), new ArrayList<>()));
            if (row.documentId() != null) {
                episode.documentIds().add(row.documentId());
            }
        }
        return List.copyOf(byId.values());
    }
}
