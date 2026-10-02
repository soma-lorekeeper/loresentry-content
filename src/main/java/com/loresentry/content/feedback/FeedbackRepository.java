package com.loresentry.content.feedback;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class FeedbackRepository {

    private final JdbcClient jdbcClient;

    FeedbackRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    long countSince(UUID userId, OffsetDateTime since) {
        return jdbcClient
                .sql("select count(*) from feedback where user_id = :user and created_at > :since")
                .param("user", userId).param("since", since)
                .query(Long.class)
                .single();
    }

    FeedbackDtos.Created insert(UUID userId, String category, String message, String page, String client) {
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
