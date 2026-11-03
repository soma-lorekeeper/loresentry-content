package com.loresentry.content.image;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ImageRepository {

    private static final String COLUMNS =
            "id, project_id, file_name, s3_key, content_type, size_bytes, status, created_at, committed_at";

    private final JdbcClient jdbcClient;

    public ImageRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    boolean projectExists(UUID ownerUserId, UUID projectId) {
        AppLogger.current().info("project exists check", LogField.projectId(projectId));
        return jdbcClient
                .sql("select 1 from projects where id = :id and owner_user_id = :owner"
                        + " and trashed_at is null")
                .param("id", projectId).param("owner", ownerUserId)
                .query(Integer.class).optional().isPresent();
    }

    ImageRow insertPending(UUID projectId, String fileName, String key, String contentType,
            long sizeBytes) {
        // 파일 이름과 S3 키는 남기지 않는다
        AppLogger.current().info("image pending insert", LogField.projectId(projectId), LogField.param("size_bytes", sizeBytes));
        return jdbcClient
                .sql("""
                        insert into image (project_id, file_name, s3_key, content_type, size_bytes)
                        values (:project, :fileName, :key, :contentType, :size)
                        returning
                        """ + COLUMNS)
                .param("project", projectId)
                .param("fileName", fileName)
                .param("key", key)
                .param("contentType", contentType)
                .param("size", sizeBytes)
                .query(ImageRepository::mapRow)
                .single();
    }

    /** 프로젝트를 조건에 넣는다. 이미지 id 만으로 찾으면 남의 프로젝트의 이미지가 보인다. */
    Optional<ImageRow> find(UUID projectId, UUID imageId) {
        AppLogger.current().info("image find", LogField.projectId(projectId), LogField.imageId(imageId));
        return jdbcClient
                .sql("select " + COLUMNS + " from image where id = :id and project_id = :project")
                .param("id", imageId).param("project", projectId)
                .query(ImageRepository::mapRow)
                .optional();
    }

    ImageRow markCommitted(UUID imageId) {
        AppLogger.current().info("image mark committed", LogField.imageId(imageId));
        return jdbcClient
                .sql("update image set status = 'COMMITTED', committed_at = now() "
                        + "where id = :id returning " + COLUMNS)
                .param("id", imageId)
                .query(ImageRepository::mapRow)
                .single();
    }

    void delete(UUID imageId) {
        AppLogger.current().info("image delete", LogField.imageId(imageId));
        jdbcClient.sql("delete from image where id = :id").param("id", imageId).update();
    }

    private static ImageRow mapRow(ResultSet rows, int rowNum) throws SQLException {
        return new ImageRow(
                rows.getObject("id", UUID.class),
                rows.getObject("project_id", UUID.class),
                rows.getString("file_name"),
                rows.getString("s3_key"),
                rows.getString("content_type"),
                rows.getLong("size_bytes"),
                rows.getString("status"),
                rows.getObject("created_at", OffsetDateTime.class),
                rows.getObject("committed_at", OffsetDateTime.class));
    }
}
