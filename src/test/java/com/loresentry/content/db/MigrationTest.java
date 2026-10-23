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
                    .containsExactly("MANUSCRIPT", "WORLDVIEW", "CHARACTER", "LOCATION",
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
            statement.execute("insert into document_relations (low_document_id, high_document_id) "
                    + "values (least('" + character + "'::uuid, '" + chapter + "'::uuid), "
                    + "greatest('" + character + "'::uuid, '" + chapter + "'::uuid))");
            String version = column(statement,
                    "insert into document_versions (document_id, source_revision_no, kind, snapshot) "
                            + "values ('" + character + "', 0, 'REFRESH_BASE', '{}'::jsonb) returning id")
                    .getFirst();
            String run = column(statement,
                    "insert into refresh_runs (project_id, requested_by, status, prompt_version) "
                            + "values ('" + project + "', uuidv7(), 'GENERATING', 'v1') returning id").getFirst();
            statement.execute("insert into refresh_document_drafts "
                    + "(refresh_run_id, target_document_id, base_version_id, "
                    + "left_source_revision_no, left_snapshot, origin_snapshot) "
                    + "values ('" + run + "', '" + character + "', '" + version + "', 0, '{}', '{}')");

            statement.execute("delete from projects where id = '" + project + "'");

            assertThat(column(statement, "select count(*)::text from document "
                    + "where project_id = '" + project + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from episode_folders "
                    + "where project_id = '" + project + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from refresh_runs "
                    + "where project_id = '" + project + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from document_properties "
                    + "where document_id = '" + character + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from document_relations "
                    + "where low_document_id = '" + character + "' "
                    + "or high_document_id = '" + character + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from document_versions "
                    + "where document_id = '" + character + "'")).containsExactly("0");
            assertThat(column(statement, "select count(*)::text from refresh_document_drafts "
                    + "where refresh_run_id = '" + run + "'")).containsExactly("0");
        }
    }

    @Test
    void createsTheImageTableWithItsStatusRules() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            String project = column(statement,
                    "insert into projects (owner_user_id, name) values (uuidv7(), 'img') returning id")
                    .getFirst();

            statement.execute("insert into image (project_id, s3_key, content_type, size_bytes) "
                    + "values ('" + project + "', 'k/1.png', 'image/png', 10)");

            // 같은 S3 키로 두 행을 만들면 어느 행이 그 객체의 기록인지 알 수 없다.
            assertThatThrownBy(() -> statement.execute(
                    "insert into image (project_id, s3_key, content_type, size_bytes) "
                            + "values ('" + project + "', 'k/1.png', 'image/png', 10)"))
                    .hasMessageContaining("uq_image_s3_key");

            // COMMITTED 라고 주장하면서 확인 시각이 없을 수는 없다.
            assertThatThrownBy(() -> statement.execute(
                    "update image set status = 'COMMITTED' where s3_key = 'k/1.png'"))
                    .hasMessageContaining("ck_image_committed_at");

            statement.execute("delete from projects where id = '" + project + "'");
            assertThat(column(statement, "select count(*)::text from image "
                    + "where project_id = '" + project + "'")).containsExactly("0");
        }
    }

    @Test
    void enforcesTheMemoScopeRules() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            String project = column(statement,
                    "insert into projects (owner_user_id, name) values (uuidv7(), 'memo') returning id")
                    .getFirst();
            String document = column(statement,
                    "insert into document (project_id, folder_id, title, rank) "
                            + "values ('" + project + "', 2, 'c', 'a0') returning id").getFirst();

            statement.execute("insert into memo (project_id, scope) values ('" + project + "', 'PROJECT')");
            statement.execute("insert into memo (project_id, scope, document_id) "
                    + "values ('" + project + "', 'FILE', '" + document + "')");

            // FILE 인데 문서가 없거나 PROJECT 인데 문서가 있으면 목록이 새거나 빠진다.
            assertThatThrownBy(() -> statement.execute(
                    "insert into memo (project_id, scope) values ('" + project + "', 'FILE')"))
                    .hasMessageContaining("ck_memo_document_matches_scope");
            assertThatThrownBy(() -> statement.execute(
                    "insert into memo (project_id, scope, document_id) "
                            + "values ('" + project + "', 'PROJECT', '" + document + "')"))
                    .hasMessageContaining("ck_memo_document_matches_scope");

            statement.execute("insert into favorite (project_id, document_id) "
                    + "values ('" + project + "', '" + document + "')");
            statement.execute("insert into workspace_state (project_id, owner_user_id, layout) "
                    + "values ('" + project + "', uuidv7(), '{}'::jsonb)");

            statement.execute("delete from projects where id = '" + project + "'");
            for (String table : new String[] { "memo", "favorite", "workspace_state" }) {
                assertThat(column(statement, "select count(*)::text from " + table
                        + " where project_id = '" + project + "'")).containsExactly("0");
            }
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

    /**
     * 최신화 요청마다 실은 문서와 revision 이 남고, run 이 지워지면 함께 지워진다. 요청 때는 추출 규칙 버전을
     * 모르므로 prompt_version 없이도 run 을 기록할 수 있어야 한다.
     */
    @Test
    void recordsTheFilesOfEachRefreshRunUntilTheRunIsGone() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(column(statement,
                    "select table_name from information_schema.tables where table_schema = 'public'"))
                    .contains("refresh_run_files", "refresh_results")
                    .doesNotContain("projection_heads");
            assertThat(column(statement,
                    "select column_name from information_schema.columns where table_name = 'refresh_runs'"))
                    .doesNotContain("input_fingerprint", "result_bucket", "result_key");

            String project = column(statement,
                    "insert into projects (owner_user_id, name) values (uuidv7(), 'Refreshed') returning id")
                    .getFirst();
            String run = column(statement,
                    "insert into refresh_runs (project_id, requested_by, status) "
                            + "values ('" + project + "', uuidv7(), 'GENERATING') returning id").getFirst();
            statement.execute("insert into refresh_run_files (refresh_run_id, file_id, revision_no) "
                    + "values ('" + run + "', uuidv7(), 3)");

            statement.execute("delete from refresh_runs where id = '" + run + "'");

            assertThat(column(statement, "select count(*)::text from refresh_run_files "
                    + "where refresh_run_id = '" + run + "'")).containsExactly("0");
        }
    }

    /**
     * 검토를 기다리는 결과(READY)도 새 요청을 막고, 결과가 없는 성공(NO_CHANGE)은 막지 않는다. 문서의 최신화 잠금은
     * run 이 지워지면 함께 풀린다.
     */
    @Test
    void blocksNewRefreshesWhileAResultAwaitsReview() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            String project = column(statement,
                    "insert into projects (owner_user_id, name) values (uuidv7(), 'Reviewed') returning id")
                    .getFirst();
            String ready = column(statement,
                    "insert into refresh_runs (project_id, requested_by, status) "
                            + "values ('" + project + "', uuidv7(), 'READY') returning id").getFirst();

            assertThatThrownBy(() -> statement.execute(
                    "insert into refresh_runs (project_id, requested_by, status) "
                            + "values ('" + project + "', uuidv7(), 'GENERATING')"))
                    .hasMessageContaining("uq_refresh_runs_one_in_progress");

            statement.execute("insert into refresh_runs (project_id, requested_by, status) "
                    + "values ('" + project + "', uuidv7(), 'NO_CHANGE')");

            String document = column(statement,
                    "insert into document (project_id, folder_id, title, rank, refresh_lock_run_id) "
                            + "values ('" + project + "', 2, 'locked', 'a0', '" + ready + "') returning id")
                    .getFirst();
            statement.execute("delete from refresh_runs where id = '" + ready + "'");

            assertThat(column(statement, "select coalesce(refresh_lock_run_id::text, 'none') from document "
                    + "where id = '" + document + "'")).containsExactly("none");
        }
    }

    /**
     * 기존 문서 작업본(EDIT)은 대상 문서와 left 를 갖고, 새 문서 작업본(NEW)은 대상 없이 제안 이름과 분류를 갖는다.
     * 두 작업본이 같은지(matched)는 DB 가 계산한다.
     */
    @Test
    void keepsEditAndNewDraftsInShapeAndComputesWhetherTheyMatch() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            String project = column(statement,
                    "insert into projects (owner_user_id, name) values (uuidv7(), 'Drafted') returning id")
                    .getFirst();
            String run = column(statement,
                    "insert into refresh_runs (project_id, requested_by, status) "
                            + "values ('" + project + "', uuidv7(), 'GENERATING') returning id").getFirst();
            String document = column(statement,
                    "insert into document (project_id, folder_id, title, rank) "
                            + "values ('" + project + "', 2, 'hero', 'a0') returning id").getFirst();

            String edit = column(statement,
                    "insert into refresh_document_drafts (refresh_run_id, target_document_id, "
                            + "left_source_revision_no, left_snapshot, origin_snapshot) "
                            + "values ('" + run + "', '" + document + "', 0, '{\"t\":1}', '{\"t\":1}') "
                            + "returning id").getFirst();
            assertThat(column(statement, "select matched::text from refresh_document_drafts "
                    + "where id = '" + edit + "'")).containsExactly("false");

            statement.execute("update refresh_document_drafts set status = 'OPEN', right_snapshot = '{\"t\":1}' "
                    + "where id = '" + edit + "'");
            assertThat(column(statement, "select matched::text from refresh_document_drafts "
                    + "where id = '" + edit + "'")).containsExactly("true");

            assertThatThrownBy(() -> statement.execute(
                    "insert into refresh_document_drafts (refresh_run_id, target_document_id) "
                            + "values ('" + run + "', '" + document + "')"))
                    .hasMessageContaining("ck_refresh_document_drafts_snapshots");

            statement.execute("insert into refresh_document_drafts (refresh_run_id, kind, candidate_id, "
                    + "folder_code, status) values ('" + run + "', 'NEW', 'new:CHARACTER:a', 'CHARACTER', 'OPEN')");
            assertThatThrownBy(() -> statement.execute(
                    "insert into refresh_document_drafts (refresh_run_id, kind, candidate_id, folder_code, status) "
                            + "values ('" + run + "', 'NEW', 'new:MANUSCRIPT:a', 'MANUSCRIPT', 'OPEN')"))
                    .hasMessageContaining("ck_refresh_document_drafts_kind");
            assertThatThrownBy(() -> statement.execute(
                    "insert into refresh_document_drafts (refresh_run_id, kind, candidate_id, folder_code, status) "
                            + "values ('" + run + "', 'NEW', 'new:CHARACTER:a', 'CHARACTER', 'OPEN')"))
                    .hasMessageContaining("uq_refresh_document_drafts_run_candidate");
        }
    }
}
