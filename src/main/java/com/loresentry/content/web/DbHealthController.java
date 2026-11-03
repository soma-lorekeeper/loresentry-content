package com.loresentry.content.web;

import java.util.Map;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DbHealthController {

    private static final String SERVICE = "content-api";

    private final JdbcClient jdbcClient;

    public DbHealthController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/health/db")
    public ResponseEntity<Map<String, Object>> databaseHealth() {
        AppLogger.current().info("database health probe requested");
        try {
            Map<String, Object> row = jdbcClient
                    .sql("select current_database() as database")
                    .query()
                    .singleRow();

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "service", SERVICE,
                    // DB 사용자 이름·버전은 싣지 않는다. 로그인한 사용자도 gateway /health/db 로 이 본문을 볼 수 있다
                    "database", row.get("database")));
        } catch (DataAccessException exception) {
            // 예외 메시지에는 접속 정보가 섞일 수 있어 종류와 프레임만 남긴다. 503 결과 줄(request completed)에도 종류를 붙인다
            AppLogger.current().addToOutcome(LogField.errorType(exception.getClass().getName()));
            AppLogger.current().warn("database health probe failed", LogField.errorType(exception.getClass().getName()),
                    LogField.safeStackTrace(exception));
            // 본문에도 예외 메시지(DB 주소·사용자가 섞일 수 있다)를 싣지 않고 고정 문구만 싣는다. 원인은 위 warn 줄로 본다
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "error",
                    "service", SERVICE,
                    "error", "Database unavailable"));
        }
    }
}
