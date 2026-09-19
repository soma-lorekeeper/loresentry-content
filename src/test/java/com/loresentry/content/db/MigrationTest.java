package com.loresentry.content.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @BeforeAll
    static void migrate() {
        var result = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        assertThat(result.success).isTrue();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static List<String> column(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    @Test
    void createsTheContentTables() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(column(statement,
                    "select table_name from information_schema.tables where table_schema = 'public'"))
                    .contains("projects", "base_folders", "episode_folders", "document",
                            "document_properties", "document_relations", "document_versions",
                            "refresh_runs", "refresh_document_drafts", "outbox_events");

            assertThat(column(statement,
                    "select column_name from information_schema.columns "
                            + "where table_name = 'refresh_document_drafts'"))
                    .doesNotContain("final_side");
        }
    }

    @Test
    void seedsTheBaseFolders() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(column(statement, "select code from base_folders order by position"))
                    .containsExactly("WORLDVIEW", "CHARACTER", "LOCATION", "MANUSCRIPT",
                            "ORGANIZATION", "ITEM", "EVENT");
        }
    }

    @Test
    void enforcesTheDocumentAndRefreshRules() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            String project = column(statement,
                    "insert into projects (owner_user_id, name) values (uuidv7(), 'p') returning id").getFirst();
            String episode = column(statement,
                    "insert into episode_folders (project_id, created_by_user_id, name, rank) "
                            + "values ('" + project + "', uuidv7(), 'ep', 'a0') returning id").getFirst();

            statement.execute("insert into document (project_id, folder_id, episode_id, title, rank) "
                    + "values ('" + project + "', 4, '" + episode + "', 'chapter', 'a0')");

            assertThatThrownBy(() -> statement.execute(
                    "insert into document (project_id, folder_id, episode_id, title, rank) "
                            + "values ('" + project + "', 2, '" + episode + "', 'character', 'a0')"))
                    .hasMessageContaining("ck_document_episode_in_manuscript");

            statement.execute("insert into refresh_runs (project_id, requested_by, status, prompt_version) "
                    + "values ('" + project + "', uuidv7(), 'GENERATING', 'v1')");

            assertThatThrownBy(() -> statement.execute(
                    "insert into refresh_runs (project_id, requested_by, status, prompt_version) "
                            + "values ('" + project + "', uuidv7(), 'CAPTURING_BASE', 'v1')"))
                    .hasMessageContaining("uq_refresh_runs_one_in_progress");
        }
    }
}
