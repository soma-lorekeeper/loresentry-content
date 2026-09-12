package com.loresentry.content.web;

import java.util.Map;

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
        try {
            Map<String, Object> row = jdbcClient
                    .sql("select current_database() as database, current_user as username, version() as version")
                    .query()
                    .singleRow();

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "service", SERVICE,
                    "database", row.get("database"),
                    "username", row.get("username"),
                    "version", row.get("version")));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "error",
                    "service", SERVICE,
                    "error", String.valueOf(exception.getMostSpecificCause().getMessage())));
        }
    }
}
