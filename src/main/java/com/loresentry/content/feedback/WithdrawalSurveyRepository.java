package com.loresentry.content.feedback;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class WithdrawalSurveyRepository {

    private final JdbcClient jdbcClient;

    WithdrawalSurveyRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void insert(String[] reasons, String comment) {
        jdbcClient.sql("insert into withdrawal_survey (reasons, comment) values (:reasons, :comment)")
                .param("reasons", reasons).param("comment", comment)
                .update();
    }
}
