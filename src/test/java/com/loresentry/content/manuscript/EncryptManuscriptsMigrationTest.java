package com.loresentry.content.manuscript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class EncryptManuscriptsMigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    private static final byte[] MASTER = new byte[32];

    private static final LocalKeyWrapper WRAPPER = new LocalKeyWrapper(MASTER);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static UUID project;

    private static UUID document;

    private static UUID legacy;

    private static UUID version;

    private static UUID run;

    private static UUID draft;

    private static UUID event;

    private static UUID deletedProjectEvent;

    private static final String BODY_JSON = "{\"doc\": {\"type\": \"doc\", \"content\": [{\"type\": \"paragraph\", "
            + "\"content\": [{\"text\": \"유중혁은 회귀자다. 😀\", \"type\": \"text\"}]}]}, \"schema_version\": 1}";

    private static final String BODY_TEXT = "유중혁은 회귀자다. 😀";

    @BeforeAll
    static void migrateThroughPlaintextAndEncrypt() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("18")
                .load()
                .migrate();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            project = uuid(statement, "insert into projects (owner_user_id, name) values (uuidv7(), '회귀') returning id");
            document = uuid(statement, "insert into document (project_id, folder_id, title, rank, body_json, body_text) "
                    + "values ('" + project + "', 2, '유중혁', 'a0', '" + BODY_JSON + "'::jsonb, '" + BODY_TEXT
                    + "') returning id");
            legacy = uuid(statement, "insert into document (project_id, folder_id, title, rank, body_md, body_text) "
                    + "values ('" + project + "', 2, '옛 문서', 'a1', '# 옛 본문', '옛 본문') returning id");
            version = uuid(statement, "insert into document_versions (document_id, source_revision_no, kind, snapshot) "
                    + "values ('" + document + "', 0, 'REFRESH_BASE', '{\"title\": \"유중혁\"}'::jsonb) returning id");
            run = uuid(statement, "insert into refresh_runs (project_id, requested_by, status, prompt_version) "
                    + "values ('" + project + "', uuidv7(), 'READY', 'v1') returning id");
            draft = uuid(statement, "insert into refresh_document_drafts (refresh_run_id, target_document_id, "
                    + "base_version_id, left_source_revision_no, left_snapshot, origin_snapshot, right_snapshot, "
                    + "status) values ('" + run + "', '" + document + "', '" + version + "', 0, "
                    + "'{\"title\": \"a\"}', '{\"title\": \"a\"}', '{\"title\": \"a\"}', 'OPEN') returning id");
            statement.execute("insert into refresh_results (refresh_run_id, payload) values ('" + run
                    + "', '{\"outcome\": \"PROPOSED\"}')");
            event = uuid(statement, "insert into outbox_events (event_type, aggregate_id, event_key, payload) values "
                    + "('FileChanged', '" + document + "', '" + project + "', '{\"project_id\": \"" + project
                    + "\", \"file_id\": \"" + document + "\", \"change_type\": \"UPDATED\", \"file\": {\"title\": "
                    + "\"유중혁\", \"body_text\": \"옛 스냅샷\"}, \"relations\": []}') returning id");
            UUID gone = UUID.randomUUID();
            deletedProjectEvent = uuid(statement, "insert into outbox_events (event_type, aggregate_id, event_key, "
                    + "payload) values ('FileChanged', uuidv7(), '" + gone + "', '{\"project_id\": \"" + gone
                    + "\", \"file_id\": \"" + UUID.randomUUID() + "\", \"file\": {\"body_text\": \"지운 본문\"}}') "
                    + "returning id");
        }
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .javaMigrations(new V19__EncryptManuscripts(WRAPPER))
                .load()
                .migrate();
    }

    @Test
    void givesTheProjectAKeyAndEncryptsEveryBody() throws Exception {
        byte[] key = projectKey();
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select body_json_enc, body_md_enc, body_text_enc, "
                        + "body_text_mac, body_text_length from document where id = '" + document + "'")) {
            rows.next();
            assertThat(open(key, document, Purpose.BODY_JSON, rows.getBytes(1))).isEqualTo(BODY_JSON);
            assertThat(rows.getBytes(2)).isNull();
            assertThat(open(key, document, Purpose.BODY_TEXT, rows.getBytes(3))).isEqualTo(BODY_TEXT);
            assertThat(rows.getBytes(4)).isEqualTo(Envelope.mac(key, BODY_TEXT));
            assertThat(rows.getInt(5)).isEqualTo(BODY_TEXT.codePointCount(0, BODY_TEXT.length()));
        }
        try (Connection connection = connect(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select body_json_enc, body_md_enc, body_text_enc "
                        + "from document where id = '" + legacy + "'")) {
            rows.next();
            assertThat(rows.getBytes(1)).isNull();
            assertThat(open(key, legacy, Purpose.BODY_MD, rows.getBytes(2))).isEqualTo("# 옛 본문");
            assertThat(open(key, legacy, Purpose.BODY_TEXT, rows.getBytes(3))).isEqualTo("옛 본문");
        }
    }

    @Test
    void encryptsVersionsDraftsAndResults() throws Exception {
        byte[] key = projectKey();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(JSON.readTree(open(key, document, Purpose.VERSION_SNAPSHOT,
                    bytes(statement, "select snapshot_enc from document_versions where id = '" + version + "'"))))
                    .isEqualTo(JSON.readTree("{\"title\":\"유중혁\"}"));
            for (String side : new String[] {"left", "right", "origin"}) {
                assertThat(open(key, draft, Purpose.valueOf("DRAFT_" + side.toUpperCase()), bytes(statement,
                        "select " + side + "_snapshot_enc from refresh_document_drafts where id = '" + draft + "'")))
                        .isEqualTo("{\"title\": \"a\"}");
            }
            try (ResultSet rows = statement.executeQuery(
                    "select is_matched from refresh_document_drafts where id = '" + draft + "'")) {
                rows.next();
                assertThat(rows.getBoolean(1)).isTrue();
            }
            assertThat(open(key, run, Purpose.REFRESH_RESULT,
                    bytes(statement, "select payload_enc from refresh_results where refresh_run_id = '" + run + "'")))
                    .isEqualTo("{\"outcome\": \"PROPOSED\"}");
        }
    }

    @Test
    void movesOutboxBodiesIntoCiphertext() throws Exception {
        byte[] key = projectKey();
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            JsonNode payload = JSON.readTree(text(statement,
                    "select payload::text from outbox_events where id = '" + event + "'"));
            assertThat(payload.at("/file").has("body_text")).isFalse();
            assertThat(open(key, document, Purpose.BODY_TEXT,
                    Manuscripts.fromBase64(payload.at("/file/body_text_enc").asString()))).isEqualTo("옛 스냅샷");
            assertThat(Manuscripts.fromBase64(payload.get("wrapped_key").asString()))
                    .isEqualTo(bytes(statement, "select wrapped_key from project_keys where project_id = '"
                            + project + "'"));

            JsonNode orphan = JSON.readTree(text(statement,
                    "select payload::text from outbox_events where id = '" + deletedProjectEvent + "'"));
            assertThat(orphan.at("/file").has("body_text")).isFalse();
            assertThat(orphan.at("/file/body_text_enc").isNull()).isTrue();
            assertThat(orphan.get("wrapped_key").isNull()).isTrue();
        }
    }

    @Test
    void refusesDocumentsWithoutCiphertextAfterwards() throws Exception {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("insert into document (project_id, folder_id, title, rank) "
                    + "values ('" + project + "', 2, '평문', 'a2')")).isInstanceOf(SQLException.class);
        }
    }

    private static byte[] projectKey() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            return WRAPPER.unwrap(project,
                    bytes(statement, "select wrapped_key from project_keys where project_id = '" + project + "'"));
        }
    }

    private static String open(byte[] key, UUID subject, Purpose purpose, byte[] envelope) {
        return new String(Envelope.open(key, Envelope.aad(project, subject, purpose), envelope), StandardCharsets.UTF_8);
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static UUID uuid(Statement statement, String sql) throws SQLException {
        return UUID.fromString(text(statement, sql));
    }

    private static String text(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static byte[] bytes(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getBytes(1);
        }
    }
}
