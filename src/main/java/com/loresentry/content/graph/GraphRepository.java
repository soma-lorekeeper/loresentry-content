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
     * 관계. 저장은 양방향 두 행이지만 <b>한 쌍에 하나</b>만 준다.
     *
     * <p>관계는 대칭이라 방향에 뜻이 없다. 두 행을 그대로 주면 관계도가 같은 연결에 링크를 두 개
     * 그리고, 노드 패널도 같은 문서를 두 번 나열한다.
     *
     * <p>어느 행을 남길지는 두 id 중 작은 쪽을 {@code source} 로 두어 정한다 — 임의로 고르면 요청마다
     * 방향이 달라져 화면이 흔들린다. 한쪽 행만 있는 옛 관계도 그대로 포함된다.
     *
     * <p>휴지통 문서로 향하는 행은 제외한다.
     */
    List<GraphResponses.Edge> edges(UUID projectId) {
        return jdbcClient
                .sql("""
                        select distinct on (pair_low, pair_high)
                               id, document_id, target_document_id, relation_key, description
                        from (
                            select r.id, r.document_id, r.target_document_id,
                                   r.relation_key, r.description, r.position,
                                   least(r.document_id::text, r.target_document_id::text) as pair_low,
                                   greatest(r.document_id::text, r.target_document_id::text) as pair_high
                            from document_relations r
                            join document source on source.id = r.document_id
                            join document target on target.id = r.target_document_id
                            where source.project_id = :project
                              and source.trashed_at is null and target.trashed_at is null
                        ) rows
                        order by pair_low, pair_high,
                                 (document_id::text = pair_low) desc, position, id
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
