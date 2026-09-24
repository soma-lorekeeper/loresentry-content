package com.loresentry.content.search;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SearchRepository {

    /**
     * 관련도 순서를 SQL 이 정한다(요구사항 §3.7): 제목 완전 일치 → 제목 부분 일치 → 본문 일치.
     * 동률이면 최근 수정 순이다.
     *
     * <p>전문 검색 인덱스를 쓰지 않는다. 한 프로젝트의 문서 수가 수백 단위이고, 요구사항이 요구하는
     * 것은 부분 문자열 일치다. {@code tsvector}는 형태소 분석기를 골라야 하고 한국어에서는 그 선택이
     * 결과를 크게 바꾼다 — 검색 품질을 다룰 때 함께 결정할 일이다.
     */
    private static final String SEARCH = """
            select d.id, d.title, f.code as folder_code, e.name as episode_name,
                   d.body_md, d.updated_at,
                   case
                     when lower(d.title) = lower(:query) then 0
                     when position(lower(:query) in lower(d.title)) > 0 then 1
                     else 2
                   end as bucket
            from document d
            join base_folders f on f.id = d.folder_id
            left join episode_folders e on e.id = d.episode_id
            join projects p on p.id = d.project_id
            where d.project_id = :project
              and p.owner_user_id = :owner
              and d.trashed_at is null
              and (position(lower(:query) in lower(d.title)) > 0
                   or position(lower(:query) in lower(d.body_md)) > 0)
            order by bucket, d.updated_at desc
            limit :limit
            """;

    private final JdbcClient jdbcClient;

    public SearchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    record Row(UUID id, String title, String folderCode, String episodeName, String bodyMd,
            OffsetDateTime updatedAt) {
    }

    List<Row> search(UUID ownerUserId, UUID projectId, String query, int limit) {
        return jdbcClient.sql(SEARCH)
                .param("project", projectId)
                .param("owner", ownerUserId)
                .param("query", query)
                .param("limit", limit)
                .query((rows, index) -> new Row(
                        rows.getObject("id", UUID.class),
                        rows.getString("title"),
                        rows.getString("folder_code"),
                        rows.getString("episode_name"),
                        rows.getString("body_md"),
                        rows.getObject("updated_at", OffsetDateTime.class)))
                .list();
    }
}
