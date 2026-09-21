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
    void holdsProjectNamesUpToTheRequirementLength() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(column(statement,
                    "select character_maximum_length from information_schema.columns "
                            + "where table_name = 'projects' and column_name = 'name'"))
                    .containsExactly("255");
        }
    }

    @Test
    void rejectsDuplicateActiveProjectNamesPerOwnerIgnoringCase() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            String owner = column(statement, "select uuidv7()").getFirst();

            statement.execute("insert into projects (owner_user_id, name) "
                    + "values ('" + owner + "', 'Glass Garden')");

            assertThatThrownBy(() -> statement.execute("insert into projects (owner_user_id, name) "
                    + "values ('" + owner + "', 'glass garden')"))
                    .hasMessageContaining("uq_projects_owner_active_name");

            // 다른 소유자는 같은 이름을 쓸 수 있다.
            statement.execute("insert into projects (owner_user_id, name) "
                    + "values (uuidv7(), 'Glass Garden')");
        }
    }

    @Test
    void freesTheNameOnceTheProjectIsTrashed() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            String owner = column(statement, "select uuidv7()").getFirst();

            String trashed = column(statement, "insert into projects (owner_user_id, name, trashed_at) "
                    + "values ('" + owner + "', 'Winter Notes', now()) returning id").getFirst();
            statement.execute("insert into projects (owner_user_id, name) "
                    + "values ('" + owner + "', 'Winter Notes')");

            // 부분 인덱스가 활성 행만 보므로 휴지통 이름은 비켜 준다. 반대로 복원은 막힌다.
            assertThatThrownBy(() -> statement.execute(
                    "update projects set trashed_at = null where id = '" + trashed + "'"))
                    .hasMessageContaining("uq_projects_owner_active_name");
        }
    }

    @Test
    void deletesAProjectWithEverythingUnderIt() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            String project = column(statement,
                    "insert into projects (owner_user_id, name) values (uuidv7(), 'Doomed') returning id")
                    .getFirst();
            String episode = column(statement,
                    "insert into episode_folders (project_id, created_by_user_id, name, rank) "
                            + "values ('" + project + "', uuidv7(), 'ep', 'a0') returning id").getFirst();
            String chapter = column(statement,
                    "insert into document (project_id, folder_id, episode_id, title, rank) "
                            + "values ('" + project + "', 4, '" + episode + "', 'chapter', 'a0') returning id")
                    .getFirst();
            String character = column(statement,
                    "insert into document (project_id, folder_id, title, rank) "
                            + "values ('" + project + "', 2, 'character', 'a1') returning id").getFirst();

            statement.execute("insert into document_properties (document_id, property_key, text_value, position) "
                    + "values ('" + character + "', 'description', 'x', 10)");
            statement.execute("insert into document_relations "
                    + "(document_id, relation_key, target_document_id, position) "
                    + "values ('" + character + "', 'related_manuscript', '" + chapter + "', 10)");
            String version = column(statement,
                    "insert into document_versions (document_id, source_revision_no, kind, snapshot) "
                            + "values ('" + character + "', 0, 'REFRESH_BASE', '{}'::jsonb) returning id")
                    .getFirst();
            String run = column(statement,
                    "insert into refresh_runs (project_id, requested_by, status, prompt_version) "
                            + "values ('" + project + "', uuidv7(), 'GENERATING', 'v1') returning id").getFirst();
            statement.execute("insert into refresh_document_drafts "
                    + "(refresh_run_id, target_document_id, base_version_id) "
                    + "values ('" + run + "', '" + character + "', '" + version + "')");

            statement.execute("delete from projects where id = '" + project + "'");

            assertThat(column(statement, "select count(*)::text from document "
                    + "where project_id = '" + project + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from episode_folders "
                    + "where project_id = '" + project + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from refresh_runs "
                    + "where project_id = '" + project + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from document_properties "
                    + "where document_id = '" + character + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from document_versions "
                    + "where document_id = '" + character + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from refresh_document_drafts "
                    + "where refresh_run_id = '" + run + "'")).containsExactly("0");
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
