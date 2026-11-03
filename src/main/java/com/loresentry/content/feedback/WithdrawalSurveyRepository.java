package com.loresentry.content.feedback;

import com.loresentry.content.logging.AppLogger;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class WithdrawalSurveyRepository {

    private final JdbcClient jdbcClient;

    WithdrawalSurveyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void insert(String[] reasons, String comment) {
        // 이유 개수는 서비스 줄에 있고, 의견은 남기지 않는다
        AppLogger.current().info("withdrawal survey insert");
        jdbcClient.sql("insert into withdrawal_survey (reasons, comment) values (:reasons, :comment)")
                .param("reasons", reasons).param("comment", comment)
                .update();
    }
}
