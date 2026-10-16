package com.loresentry.content.refresh;

import com.loresentry.content.outbox.ContentEvents;
import com.loresentry.content.web.ContentFailure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "graph-refresh.enabled", havingValue = "true")
public class GraphRefreshRequests {
    private final JdbcClient jdbc;
    private final ContentEvents events;
    private final RefreshObjects objects;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String model;

    public GraphRefreshRequests(
            JdbcClient jdbc,
            ContentEvents events,
            RefreshObjects objects,
            @Value("${graph-refresh.model:gpt-6-luna}") String model) {
        this.jdbc = jdbc;
        this.events = events;
        this.objects = objects;
        this.model = model;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> request(UUID user, UUID project, List<UUID> fileIds)
            throws Exception {
        events.lock(user);
        requireProject(user, project);
        if (fileIds == null
                || fileIds.isEmpty()
                || fileIds.size() > 20
                || fileIds.stream().anyMatch(java.util.Objects::isNull)
                || fileIds.stream().distinct().count() != fileIds.size()) throw invalid();
        if (jdbc.sql(
                                "select count(*) from refresh_runs where project_id=:project and"
                                    + " status in ('CAPTURING_BASE','GENERATING')")
                        .param("project", project)
                        .query(Long.class)
                        .single()
                > 0) throw invalid();
        events.seedMissing(project);
        UUID request = UUID.randomUUID();
        java.util.ArrayList<JsonNode> documents = new java.util.ArrayList<>();
        java.util.ArrayList<Map<String, Object>> files = new java.util.ArrayList<>();
        for (UUID id : fileIds) {
            String row =
                    jdbc.sql(
                                    """
select jsonb_build_object('project_id',d.project_id,'document_id',d.id,
  'revision_no',d.revision_no,'state',case when d.trashed_at is null then 'ACTIVE' else 'TRASHED' end,
  'title',d.title,'folder_code',f.code,
  'body_text',case when d.trashed_at is null then d.body_text else null end,
  'properties',coalesce((select jsonb_agg(jsonb_build_array(property_key,text_value) order by position)
      from document_properties where document_id=d.id),'[]'::jsonb),
  'relations',coalesce((select jsonb_agg(jsonb_build_object('project_id',d.project_id,
      'document_id',d.id,'target_document_id',other.id,'relation_key',bf.relation_key,'description',r.description) order by r.id)
      from document_relations r join document other on other.id=case when r.low_document_id=d.id then r.high_document_id else r.low_document_id end
      join base_folders bf on bf.id=other.folder_id
      where (r.low_document_id=d.id or r.high_document_id=d.id) and other.trashed_at is null),'[]'::jsonb))::text
from document d join base_folders f on f.id=d.folder_id where d.id=:id and d.project_id=:project
""")
                            .param("id", id)
                            .param("project", project)
                            .query(String.class)
                            .optional()
                            .orElseThrow(GraphRefreshRequests::invalid);
            JsonNode document = json.readTree(row);
            String body = document.path("body_text").asString("");
            if (body.codePointCount(0, body.length()) > 5000) throw invalid();
            documents.add(document);
            files.add(
                    Map.of(
                            "file_id",
                            id,
                            "revision_no",
                            document.get("revision_no").asLong(),
                            "state",
                            document.get("state").asString()));
        }
        byte[] input =
                json.writeValueAsString(
                                Map.of(
                                        "schema_version",
                                        "refresh-input-v1",
                                        "project_id",
                                        project,
                                        "request_id",
                                        request,
                                        "documents",
                                        documents))
                        .getBytes(StandardCharsets.UTF_8);
        String fingerprint =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        String key = "refresh/" + project + "/" + request + "/input.json";
        // No DB event is committed if S3 upload fails. An orphan after rollback expires by
        // lifecycle.
        objects.put(key, input);
        List<UUID> required =
                jdbc.sql(
                                "select event_id from projection_heads where project_id=:project"
                                    + " order by file_id")
                        .param("project", project)
                        .query(UUID.class)
                        .list();
        jdbc.sql(
                        """
insert into refresh_runs(id,project_id,requested_by,status,prompt_version,input_fingerprint)
values(:request,:project,:user,'GENERATING','refresh-prompt-v13',:fingerprint)
""")
                .param("request", request)
                .param("project", project)
                .param("user", user)
                .param("fingerprint", fingerprint)
                .update();
        Map<String, Object> payload =
                Map.of(
                        "project_id",
                        project,
                        "request_id",
                        request,
                        "user_id",
                        user,
                        "input_fingerprint",
                        fingerprint,
                        "input",
                        Map.of("bucket", objects.bucket(), "key", key),
                        "files",
                        files,
                        "required_events",
                        required,
                        "model_settings",
                        Map.of(
                                "model",
                                model,
                                "prompt_version",
                                "refresh-prompt-v13",
                                "schema_version",
                                "refresh-candidate-v7"));
        jdbc.sql(
                        """
                        insert into outbox_events(event_type,aggregate_id,event_key,payload)
                        values('GraphRefreshRequested',:request,:project::text,:payload::jsonb)
                        """)
                .param("request", request)
                .param("project", project)
                .param("payload", json.writeValueAsString(payload))
                .update();
        return Map.of("request_id", request, "status", "GENERATING");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID user, UUID project, UUID request) {
        requireProject(user, project);
        return jdbc.sql(
                        """
select r.status,s.payload::text from refresh_runs r left join refresh_results s on s.refresh_run_id=r.id
where r.id=:request and r.project_id=:project
""")
                .param("request", request)
                .param("project", project)
                .query(
                        (rs, n) -> {
                            var result = new java.util.LinkedHashMap<String, Object>();
                            result.put("request_id", request);
                            result.put("status", rs.getString("status"));
                            String payload = rs.getString("payload");
                            result.put("result", payload == null ? null : json.readTree(payload));
                            return (Map<String, Object>) result;
                        })
                .optional()
                .orElseThrow(GraphRefreshRequests::invalid);
    }

    private void requireProject(UUID user, UUID project) {
        if (jdbc.sql(
                        "select 1 from projects where id=:project and owner_user_id=:user and"
                            + " trashed_at is null")
                .param("project", project)
                .param("user", user)
                .query(Integer.class)
                .optional()
                .isEmpty()) throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
    }

    private static ContentFailure invalid() {
        return new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
    }
}
