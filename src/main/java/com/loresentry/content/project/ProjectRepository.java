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

    private final JdbcClient jdbcClient;

    public ProjectRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    List<Project> findActive(UUID ownerUserId) {
        return jdbcClient
                .sql("select " + COLUMNS + " from projects "
                        + "where owner_user_id = :owner and trashed_at is null "
                        + "order by updated_at desc, id desc")
                .param("owner", ownerUserId)
                .query(ProjectRepository::mapRow)
                .list();
    }

    List<Project> findTrashed(UUID ownerUserId) {
        return jdbcClient
                .sql("select " + COLUMNS + " from projects "
                        + "where owner_user_id = :owner and trashed_at is not null "
                        + "order by trashed_at desc, id desc")
                .param("owner", ownerUserId)
                .query(ProjectRepository::mapRow)
                .list();
    }

    /** 휴지통 여부와 무관하게 찾는다. 상태 전이는 현재 상태를 알아야 판단할 수 있다. */
    Optional<Project> findById(UUID ownerUserId, UUID projectId) {
        return jdbcClient
                .sql("select " + COLUMNS + " from projects where id = :id and owner_user_id = :owner")
                .param("id", projectId)
                .param("owner", ownerUserId)
                .query(ProjectRepository::mapRow)
                .optional();
    }

    Project insert(UUID ownerUserId, String name, String description) {
        return jdbcClient
                .sql("insert into projects (owner_user_id, name, description) "
                        + "values (:owner, :name, :description) returning " + COLUMNS)
                .param("owner", ownerUserId)
                .param("name", name)
                .param("description", description)
                .query(ProjectRepository::mapRow)
                .single();
    }

    Project update(UUID projectId, String name, String description) {
        return jdbcClient
                .sql("update projects set name = :name, description = :description, updated_at = now() "
                        + "where id = :id returning " + COLUMNS)
                .param("id", projectId)
                .param("name", name)
                .param("description", description)
                .query(ProjectRepository::mapRow)
                .single();
    }

    void markTrashed(UUID projectId) {
        jdbcClient
                .sql("update projects set trashed_at = now(), updated_at = now() "
                        + "where id = :id and trashed_at is null")
                .param("id", projectId)
                .update();
    }

    Project markRestored(UUID projectId) {
        return jdbcClient
                .sql("update projects set trashed_at = null, updated_at = now() "
                        + "where id = :id returning " + COLUMNS)
                .param("id", projectId)
                .query(ProjectRepository::mapRow)
                .single();
    }

    /** 문서·에피소드·버전·최신화 작업본은 외래 키 CASCADE가 함께 지운다 (V3). */
    void delete(UUID projectId) {
        jdbcClient.sql("delete from projects where id = :id")
                .param("id", projectId)
                .update();
    }

    private static Project mapRow(java.sql.ResultSet rows, int rowNum) throws java.sql.SQLException {
        return new Project(
                rows.getObject("id", UUID.class),
                rows.getObject("owner_user_id", UUID.class),
                rows.getString("name"),
                rows.getString("description"),
                rows.getObject("trashed_at", OffsetDateTime.class),
                rows.getObject("created_at", OffsetDateTime.class),
                rows.getObject("updated_at", OffsetDateTime.class));
    }
}
