package com.loresentry.content.project;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectRepository {

    private static final String COLUMNS =
            "id, owner_user_id, name, description, trashed_at, created_at, updated_at";

    /**
     * 프로젝트마다 마지막으로 작업한 문서를 함께 읽는다. 목록에서 N+1 질의를 만들지 않으려고
     * {@code LATERAL} 로 프로젝트 한 행당 한 번만 찾는다 — 인덱스
     * {@code ix_document_active_project} 가 있어 프로젝트별 상위 1건은 싸다.
     */
    private static final String SELECT = """
            select p.id, p.owner_user_id, p.name, p.description, p.trashed_at,
                   p.created_at, p.updated_at,
                   last_file.id as last_file_id, last_file.title as last_file_title
            from projects p
            left join lateral (
                select d.id, d.title
                from document d
                where d.project_id = p.id and d.trashed_at is null
                order by d.updated_at desc, d.id desc
                limit 1
            ) last_file on true
            """;

    private final JdbcClient jdbcClient;

    public ProjectRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    List<Project> findActive(UUID ownerUserId) {
        return jdbcClient
                .sql(SELECT + " where p.owner_user_id = :owner and p.trashed_at is null "
                        + "order by p.updated_at desc, p.id desc")
                .param("owner", ownerUserId)
                .query(ProjectRepository::mapRow)
                .list();
    }

    List<Project> findTrashed(UUID ownerUserId) {
        return jdbcClient
                .sql(SELECT + " where p.owner_user_id = :owner and p.trashed_at is not null "
                        + "order by p.trashed_at desc, p.id desc")
                .param("owner", ownerUserId)
                .query(ProjectRepository::mapRow)
                .list();
    }

    /** 휴지통 여부와 무관하게 찾는다. 상태 전이는 현재 상태를 알아야 판단할 수 있다. */
    Optional<Project> findById(UUID ownerUserId, UUID projectId) {
        return jdbcClient
                .sql(SELECT + " where p.id = :id and p.owner_user_id = :owner")
                .param("id", projectId)
                .param("owner", ownerUserId)
                .query(ProjectRepository::mapRow)
                .optional();
    }

    Project insert(UUID ownerUserId, String name, String description) {
        UUID id = jdbcClient
                .sql("insert into projects (owner_user_id, name, description) "
                        + "values (:owner, :name, :description) returning id")
                .param("owner", ownerUserId)
                .param("name", name)
                .param("description", description)
                .query(UUID.class)
                .single();
        return findById(ownerUserId, id).orElseThrow();
    }

    Project update(UUID ownerUserId, UUID projectId, String name, String description) {
        jdbcClient
                .sql("update projects set name = :name, description = :description, updated_at = now() "
                        + "where id = :id")
                .param("id", projectId)
                .param("name", name)
                .param("description", description)
                .update();
        return findById(ownerUserId, projectId).orElseThrow();
    }

    void markTrashed(UUID projectId) {
        jdbcClient
                .sql("update projects set trashed_at = now(), updated_at = now() "
                        + "where id = :id and trashed_at is null")
                .param("id", projectId)
                .update();
    }

    Project markRestored(UUID ownerUserId, UUID projectId) {
        jdbcClient
                .sql("update projects set trashed_at = null, updated_at = now() where id = :id")
                .param("id", projectId)
                .update();
        return findById(ownerUserId, projectId).orElseThrow();
    }

    /** 문서·에피소드·버전·최신화 작업본은 외래 키 CASCADE가 함께 지운다 (V3). */
    void delete(UUID projectId) {
        jdbcClient.sql("delete from projects where id = :id")
                .param("id", projectId)
                .update();
    }

    private static Project mapRow(java.sql.ResultSet rows, int rowNum) throws java.sql.SQLException {
        UUID lastFileId = rows.getObject("last_file_id", UUID.class);
        return new Project(
                rows.getObject("id", UUID.class),
                rows.getObject("owner_user_id", UUID.class),
                rows.getString("name"),
                rows.getString("description"),
                rows.getObject("trashed_at", OffsetDateTime.class),
                rows.getObject("created_at", OffsetDateTime.class),
                rows.getObject("updated_at", OffsetDateTime.class),
                lastFileId == null
                        ? null
                        : new Project.LastFile(lastFileId, rows.getString("last_file_title")));
    }
}
