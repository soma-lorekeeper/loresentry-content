package com.loresentry.content.manuscript;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class V19__EncryptManuscripts extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V19__EncryptManuscripts.class);

    private final KeyWrapper wrapper;

    private final JsonMapper json = JsonMapper.builder().build();

    V19__EncryptManuscripts(KeyWrapper wrapper) {
        this.wrapper = wrapper;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        Map<UUID, byte[]> keys = projectKeys(connection);
        int documents = documents(connection, keys);
        int versions = versions(connection, keys);
        int drafts = drafts(connection, keys);
        int results = results(connection, keys);
        int events = outbox(connection, keys);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    ALTER TABLE document
                        ALTER COLUMN body_text_enc SET NOT NULL,
                        ALTER COLUMN body_text_mac SET NOT NULL,
                        ALTER COLUMN body_text_length SET NOT NULL,
                        ADD CONSTRAINT ck_document_body_enc CHECK (body_json_enc IS NOT NULL OR body_md_enc IS NOT NULL)
                    """);
            statement.execute("ALTER TABLE document_versions ALTER COLUMN snapshot_enc SET NOT NULL");
            statement.execute("ALTER TABLE refresh_results ALTER COLUMN payload_enc SET NOT NULL");
        }
        log.info("manuscript encryption: projects={}, documents={}, versions={}, drafts={}, results={}, events={}",
                keys.size(), documents, versions, drafts, results, events);
    }

    private Map<UUID, byte[]> projectKeys(Connection connection) throws SQLException {
        Map<UUID, byte[]> keys = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "select p.id, k.wrapped_key from projects p left join project_keys k on k.project_id = p.id")) {
            while (rows.next()) {
                UUID projectId = rows.getObject("id", UUID.class);
                byte[] wrapped = rows.getBytes("wrapped_key");
                keys.put(projectId, wrapped == null ? null : wrapper.unwrap(projectId, wrapped));
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into project_keys (project_id, wrapped_key) values (?, ?)")) {
            for (Map.Entry<UUID, byte[]> entry : keys.entrySet()) {
                if (entry.getValue() == null) {
                    KeyWrapper.Generated generated = wrapper.generate(entry.getKey());
                    insert.setObject(1, entry.getKey());
                    insert.setBytes(2, generated.wrapped());
                    insert.addBatch();
                    entry.setValue(generated.plaintext());
                }
            }
            insert.executeBatch();
        }
        return keys;
    }

    private int documents(Connection connection, Map<UUID, byte[]> keys) throws SQLException {
        int count = 0;
        try (Statement select = connection.createStatement();
                PreparedStatement update = connection.prepareStatement("""
                        update document
                        set body_json_enc = ?, body_md_enc = ?, body_text_enc = ?, body_text_mac = ?,
                            body_text_length = ?
                        where id = ?
                        """)) {
            select.setFetchSize(200);
            try (ResultSet rows = select.executeQuery(
                    "select id, project_id, body_json::text as body_json, body_md, body_text from document")) {
                while (rows.next()) {
                    UUID id = rows.getObject("id", UUID.class);
                    UUID projectId = rows.getObject("project_id", UUID.class);
                    byte[] key = keys.get(projectId);
                    String bodyJson = rows.getString("body_json");
                    String bodyText = rows.getString("body_text");
                    bodyText = bodyText == null ? "" : bodyText;
                    update.setBytes(1, bodyJson == null ? null : seal(key, projectId, id, Purpose.BODY_JSON, bodyJson));
                    update.setBytes(2, bodyJson == null
                            ? seal(key, projectId, id, Purpose.BODY_MD, nullToEmpty(rows.getString("body_md")))
                            : null);
                    update.setBytes(3, seal(key, projectId, id, Purpose.BODY_TEXT, bodyText));
                    update.setBytes(4, Envelope.mac(key, bodyText));
                    update.setInt(5, bodyText.codePointCount(0, bodyText.length()));
                    update.setObject(6, id);
                    update.addBatch();
                    count++;
                }
            }
            update.executeBatch();
        }
        return count;
    }

    private int versions(Connection connection, Map<UUID, byte[]> keys) throws SQLException {
        int count = 0;
        try (Statement select = connection.createStatement();
                PreparedStatement update = connection.prepareStatement(
                        "update document_versions set snapshot_enc = ? where id = ?")) {
            select.setFetchSize(200);
            try (ResultSet rows = select.executeQuery("""
                    select v.id, v.document_id, d.project_id, v.snapshot::text as snapshot
                    from document_versions v join document d on d.id = v.document_id
                    """)) {
                while (rows.next()) {
                    UUID projectId = rows.getObject("project_id", UUID.class);
                    update.setBytes(1, seal(keys.get(projectId), projectId, rows.getObject("document_id", UUID.class),
                            Purpose.VERSION_SNAPSHOT, rows.getString("snapshot")));
                    update.setObject(2, rows.getObject("id", UUID.class));
                    update.addBatch();
                    count++;
                }
            }
            update.executeBatch();
        }
        return count;
    }

    private int drafts(Connection connection, Map<UUID, byte[]> keys) throws SQLException {
        int count = 0;
        try (Statement select = connection.createStatement();
                PreparedStatement update = connection.prepareStatement("""
                        update refresh_document_drafts
                        set left_snapshot_enc = ?, right_snapshot_enc = ?, origin_snapshot_enc = ?, is_matched = ?
                        where id = ?
                        """)) {
            select.setFetchSize(200);
            try (ResultSet rows = select.executeQuery("""
                    select r.id, run.project_id, r.left_snapshot::text as left_json,
                           r.right_snapshot::text as right_json, r.origin_snapshot::text as origin_json, r.matched
                    from refresh_document_drafts r join refresh_runs run on run.id = r.refresh_run_id
                    """)) {
                while (rows.next()) {
                    UUID id = rows.getObject("id", UUID.class);
                    UUID projectId = rows.getObject("project_id", UUID.class);
                    byte[] key = keys.get(projectId);
                    update.setBytes(1, seal(key, projectId, id, Purpose.DRAFT_LEFT, rows.getString("left_json")));
                    update.setBytes(2, seal(key, projectId, id, Purpose.DRAFT_RIGHT, rows.getString("right_json")));
                    update.setBytes(3, seal(key, projectId, id, Purpose.DRAFT_ORIGIN, rows.getString("origin_json")));
                    update.setBoolean(4, rows.getBoolean("matched"));
                    update.setObject(5, id);
                    update.addBatch();
                    count++;
                }
            }
            update.executeBatch();
        }
        return count;
    }

    private int results(Connection connection, Map<UUID, byte[]> keys) throws SQLException {
        int count = 0;
        try (Statement select = connection.createStatement();
                PreparedStatement update = connection.prepareStatement(
                        "update refresh_results set payload_enc = ? where refresh_run_id = ?")) {
            select.setFetchSize(50);
            try (ResultSet rows = select.executeQuery("""
                    select rr.refresh_run_id, run.project_id, rr.payload::text as payload
                    from refresh_results rr join refresh_runs run on run.id = rr.refresh_run_id
                    """)) {
                while (rows.next()) {
                    UUID runId = rows.getObject("refresh_run_id", UUID.class);
                    UUID projectId = rows.getObject("project_id", UUID.class);
                    update.setBytes(1, seal(keys.get(projectId), projectId, runId, Purpose.REFRESH_RESULT,
                            rows.getString("payload")));
                    update.setObject(2, runId);
                    update.addBatch();
                    count++;
                }
            }
            update.executeBatch();
        }
        return count;
    }

    private int outbox(Connection connection, Map<UUID, byte[]> keys) throws SQLException {
        Map<UUID, byte[]> wrappedKeys = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("select project_id, wrapped_key from project_keys")) {
            while (rows.next()) {
                wrappedKeys.put(rows.getObject("project_id", UUID.class), rows.getBytes("wrapped_key"));
            }
        }
        int count = 0;
        try (Statement select = connection.createStatement();
                PreparedStatement update = connection.prepareStatement(
                        "update outbox_events set payload = ?::jsonb where id = ?")) {
            select.setFetchSize(100);
            try (ResultSet rows = select.executeQuery(
                    "select id, payload::text as payload from outbox_events where event_type = 'FileChanged'")) {
                while (rows.next()) {
                    ObjectNode payload = (ObjectNode) json.readTree(rows.getString("payload"));
                    UUID projectId = UUID.fromString(payload.get("project_id").asString());
                    UUID fileId = UUID.fromString(payload.get("file_id").asString());
                    byte[] key = keys.get(projectId);
                    JsonNode file = payload.get("file");
                    String wrapped = null;
                    if (file instanceof ObjectNode document) {
                        JsonNode text = document.remove("body_text");
                        if (key != null) {
                            document.put("body_text_enc", Manuscripts.base64(seal(key, projectId, fileId,
                                    Purpose.BODY_TEXT, text == null || text.isNull() ? "" : text.asString())));
                            wrapped = Manuscripts.base64(wrappedKeys.get(projectId));
                        } else {
                            document.putNull("body_text_enc");
                        }
                    }
                    payload.put("wrapped_key", wrapped);
                    update.setString(1, json.writeValueAsString(payload));
                    update.setObject(2, rows.getObject("id", UUID.class));
                    update.addBatch();
                    count++;
                }
            }
            update.executeBatch();
        }
        return count;
    }

    private static byte[] seal(byte[] key, UUID projectId, UUID subjectId, Purpose purpose, String text) {
        if (text == null) {
            return null;
        }
        byte[] plain = text.getBytes(StandardCharsets.UTF_8);
        String aad = Envelope.aad(projectId, subjectId, purpose);
        byte[] sealed = Envelope.seal(key, aad, plain);
        if (!Arrays.equals(Envelope.open(key, aad, sealed), plain)) {
            throw new IllegalStateException("Manuscript round trip failed for " + subjectId);
        }
        return sealed;
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
