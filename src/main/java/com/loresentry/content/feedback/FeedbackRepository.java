package com.loresentry.content.feedback;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class FeedbackRepository {

    private final JdbcClient jdbcClient;

    FeedbackRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    long countSince(UUID userId, OffsetDateTime since) {
        AppLogger.current().info("feedback recent count get");
        return jdbcClient
                .sql("select count(*) from feedback where user_id = :user and created_at > :since")
                .param("user", userId).param("since", since)
                .query(Long.class)
                .single();
    }

    FeedbackDtos.Created insert(UUID userId, String category, String message, String page, String client) {
        // 분류는 서비스 줄에 있고, 본문·페이지·클라이언트 정보는 남기지 않는다
        AppLogger.current().info("feedback insert");
        return jdbcClient
                .sql("insert into feedback (user_id, category, message, page, client) "
                        + "values (:user, :category, :message, :page, :client) returning id, created_at")
                .param("user", userId).param("category", category).param("message", message)
                .param("page", page).param("client", client)
                .query((rows, rowNum) -> new FeedbackDtos.Created(
                        rows.getObject("id", UUID.class),
                        rows.getObject("created_at", OffsetDateTime.class)))
                .single();
    }
}
