package com.loresentry.content.media;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ImageRepository {

    private final JdbcClient jdbcClient;

    public ImageRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ImageRecord insertPending(UUID projectId, String fileName, String s3Key, String contentType, long sizeBytes) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        jdbcClient.sql("""
                insert into image (id, project_id, file_name, s3_key, content_type, size_bytes, status, created_at)
                values (:id, :projectId, :fileName, :s3Key, :contentType, :sizeBytes, :status, :createdAt)
                """)
                .param("id", id)
                .param("projectId", projectId)
                .param("fileName", fileName)
                .param("s3Key", s3Key)
                .param("contentType", contentType)
                .param("sizeBytes", sizeBytes)
                .param("status", ImageStatus.PENDING.name())
                .param("createdAt", now)
                .update();

        return new ImageRecord(id, projectId, fileName, s3Key, contentType, sizeBytes, ImageStatus.PENDING, now, null);
    }

    public Optional<ImageRecord> find(UUID projectId, UUID imageId) {
        return jdbcClient.sql("""
                select id, project_id, file_name, s3_key, content_type, size_bytes, status, created_at, committed_at
                from image
                where id = :id and project_id = :projectId
                """)
                .param("id", imageId)
                .param("projectId", projectId)
                .query(ImageRepository::map)
                .optional();
    }

    public ImageRecord markCommitted(ImageRecord image) {
        OffsetDateTime now = OffsetDateTime.now();

        jdbcClient.sql("""
                update image
                set status = :status, committed_at = :committedAt
                where id = :id
                """)
                .param("status", ImageStatus.COMMITTED.name())
                .param("committedAt", now)
                .param("id", image.id())
                .update();

        return new ImageRecord(image.id(), image.projectId(), image.fileName(), image.s3Key(), image.contentType(),
                image.sizeBytes(), ImageStatus.COMMITTED, image.createdAt(), now);
    }

    private static ImageRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new ImageRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("file_name"),
                rs.getString("s3_key"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                ImageStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("committed_at", OffsetDateTime.class));
    }
}
