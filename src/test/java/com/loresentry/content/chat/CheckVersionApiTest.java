package com.loresentry.content.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.manuscript.Purpose;
import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class CheckVersionApiTest extends ApiTestSupport {

    private UUID project;

    @BeforeEach
    void seed() throws Exception {
        project = createProject("유리 정원의 기록");
    }

    @Test
    void snapshotsTheManuscriptItHandsBack() throws Exception {
        UUID episode = createFile(project, Map.of("kind", "episode", "title", "1부"));
        UUID lena = document("CHARACTER", "레나 아르벨");
        UUID prologue = chapter(episode, "프롤로그");
        UUID chapter = chapter(episode, "1화 · 첫 번째 온실");
        save(prologue, "프롤로그", List.of("빛이 먼저 왔다."), List.of(), Map.of());
        save(chapter, "1화 · 첫 번째 온실", List.of("서윤이 문을 넘었다.", "👩‍👩‍👧 가족이 기다렸다."),
                List.of(property("description", "첫 회차 설명 🌙"), property("alias", ""),
                        property("alias", "온실 편")),
                Map.of(lena, "처음 각성한 곳 🔥"));

        MvcResult result = mockMvc.perform(as(body(post(checkPath()), request(chapter))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        JsonNode check = read(result);

        assertThat(check.propertyNames()).containsExactly("version_id", "created_at", "manuscript");
        assertThat(check.get("created_at").isString()).isTrue();
        JsonNode documents = read(mockMvc.perform(as(body(post("/internal/projects/" + project + "/chat-documents"),
                        json.writeValueAsString(Map.of("document_ids", List.of(chapter.toString()))))))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode manuscript = check.get("manuscript");
        assertThat(manuscript).isEqualTo(documents.get("manuscripts").get(0));
        assertThat(manuscript.get("body_text").stringValue()).isEqualTo("서윤이 문을 넘었다.\n👩‍👩‍👧 가족이 기다렸다.");
        assertThat(manuscript.get("revision_no").longValue()).isEqualTo(1);
        assertThat(manuscript.get("episode_name").stringValue()).isEqualTo("1부");
        assertThat(manuscript.get("properties").size()).isEqualTo(2);

        UUID versionId = UUID.fromString(check.get("version_id").stringValue());
        JsonNode expected = snapshotOf(read(mockMvc.perform(as(get("/files/" + chapter + "/content"))).andReturn()));
        JsonNode version = read(mockMvc.perform(as(get("/files/" + chapter + "/versions/" + versionId)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(version.get("id").stringValue()).isEqualTo(versionId.toString());
        assertThat(version.get("file_id").stringValue()).isEqualTo(chapter.toString());
        assertThat(version.get("kind").stringValue()).isEqualTo("SETTING_CHECK");
        assertThat(version.get("label").isNull()).isTrue();
        assertThat(version.get("source_revision_no").longValue()).isEqualTo(1);
        assertThat(version.get("created_at")).isEqualTo(check.get("created_at"));
        assertThat(version.get("snapshot")).isEqualTo(expected);
        assertThat(version.get("snapshot").get("properties").size()).isEqualTo(3);
        assertThat(version.get("snapshot").get("relations").get(0).get("target_document_id").stringValue())
                .isEqualTo(lena.toString());

        Map<String, Object> row = jdbcClient.sql("select snapshot_enc, expires_at from document_versions where id = :id")
                .param("id", versionId).query().singleRow();
        assertThat(row.get("expires_at")).isNull();
        assertThat(json.readTree(manuscripts.open(project, chapter, Purpose.VERSION_SNAPSHOT,
                (byte[]) row.get("snapshot_enc")))).isEqualTo(expected);
    }

    @Test
    void keepsOneVersionPerRevision() throws Exception {
        UUID chapter = document("MANUSCRIPT", "1화");
        save(chapter, "1화", List.of("첫 문장"), List.of(), Map.of());
        mockMvc.perform(as(body(post("/files/" + chapter + "/versions"), "{\"label\":\"초고\"}")))
                .andExpect(status().isCreated());

        JsonNode first = check(chapter);
        JsonNode again = check(chapter);
        assertThat(again).isEqualTo(first);
        assertThat(settingChecks(chapter)).isEqualTo(1);

        save(chapter, "1화", List.of("고친 문장"), List.of(), Map.of());
        JsonNode next = check(chapter);
        assertThat(next.get("version_id")).isNotEqualTo(first.get("version_id"));
        assertThat(next.get("manuscript").get("revision_no").longValue()).isEqualTo(2);
        assertThat(next.get("manuscript").get("body_text").stringValue()).isEqualTo("고친 문장");
        assertThat(check(chapter).get("version_id")).isEqualTo(next.get("version_id"));
        assertThat(settingChecks(chapter)).isEqualTo(2);

        JsonNode versions = read(mockMvc.perform(as(get("/files/" + chapter + "/versions")))
                .andExpect(status().isOk())
                .andReturn()).get("versions");
        List<String> checks = new ArrayList<>();
        for (JsonNode version : versions) {
            if ("SETTING_CHECK".equals(version.get("kind").stringValue())) {
                checks.add(version.get("id").stringValue());
            }
        }
        assertThat(checks).containsExactlyInAnyOrder(
                first.get("version_id").stringValue(), next.get("version_id").stringValue());

        mockMvc.perform(as(get("/files/" + chapter + "/versions/" + first.get("version_id").stringValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_revision_no").value(1))
                .andExpect(jsonPath("$.snapshot.body.doc.content[0].content[0].text").value("첫 문장"));
    }

    @Test
    void refusesWhatIsNotAnActiveManuscriptOfTheProject() throws Exception {
        UUID chapter = document("MANUSCRIPT", "1화");
        UUID lena = document("CHARACTER", "레나");
        UUID trashed = document("MANUSCRIPT", "버린 화");
        mockMvc.perform(as(post("/files/" + trashed + "/trash"))).andExpect(status().isNoContent());
        UUID other = createProject("다른 작품");
        UUID elsewhere = createFile(other, Map.of("kind", "document", "folder_code", "MANUSCRIPT", "title", "남의 화"));

        for (UUID id : List.of(lena, trashed, elsewhere, UUID.randomUUID())) {
            mockMvc.perform(as(body(post(checkPath()), request(id))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        }
        mockMvc.perform(as(body(post(checkPath()), request(chapter)), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mockMvc.perform(as(body(post("/internal/projects/" + UUID.randomUUID() + "/check-versions"),
                        request(chapter))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mockMvc.perform(as(body(post("/internal/projects/" + other + "/check-versions"), request(chapter))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        mockMvc.perform(body(post(checkPath()), request(chapter)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"));

        mockMvc.perform(as(post("/projects/" + project + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(body(post(checkPath()), request(chapter))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        assertThat(jdbcClient.sql("select count(*) from document_versions where kind = 'SETTING_CHECK'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void refusesAnUnreadableRequest() throws Exception {
        for (String payload : List.of("", "{}", "{\"document_id\":null}", "{\"document_id\":\"not-a-uuid\"}",
                "{\"document_id\":\"" + UUID.randomUUID() + "\",\"label\":\"x\"}")) {
            mockMvc.perform(as(body(post(checkPath()), payload)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    private String checkPath() {
        return "/internal/projects/" + project + "/check-versions";
    }

    private String request(UUID document) {
        return json.writeValueAsString(Map.of("document_id", document.toString()));
    }

    private JsonNode check(UUID document) throws Exception {
        return read(mockMvc.perform(as(body(post(checkPath()), request(document))))
                .andExpect(status().isOk())
                .andReturn());
    }

    private long settingChecks(UUID document) {
        return jdbcClient.sql("select count(*) from document_versions where document_id = :id and kind = 'SETTING_CHECK'")
                .param("id", document).query(Long.class).single();
    }

    private JsonNode snapshotOf(JsonNode content) {
        ObjectNode snapshot = json.createObjectNode();
        for (String field : List.of("title", "body", "legacy_body_md", "properties", "relations")) {
            snapshot.set(field, content.get(field));
        }
        return snapshot;
    }

    private UUID createProject(String name) throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), json.writeValueAsString(Map.of("name", name)))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(created).get("id").stringValue());
    }

    private UUID createFile(UUID projectId, Map<String, Object> request) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + projectId + "/files"),
                        json.writeValueAsString(request))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private UUID document(String folderCode, String title) throws Exception {
        return createFile(project, Map.of("kind", "document", "folder_code", folderCode, "title", title));
    }

    private UUID chapter(UUID episode, String title) throws Exception {
        return createFile(project, Map.of("kind", "document", "folder_code", "MANUSCRIPT", "title", title,
                "episode_id", episode.toString()));
    }

    private void save(UUID document, String title, List<String> paragraphs,
            List<Map<String, String>> properties, Map<UUID, String> relations) throws Exception {
        long revision = read(mockMvc.perform(as(get("/files/" + document + "/content"))).andReturn())
                .get("revision_no").longValue();
        List<Map<String, Object>> blocks = paragraphs.stream()
                .map(text -> Map.<String, Object>of("type", "paragraph",
                        "content", List.of(Map.of("type", "text", "text", text))))
                .toList();
        List<Map<String, String>> relationList = relations.entrySet().stream()
                .map(entry -> Map.of("target_document_id", entry.getKey().toString(),
                        "description", entry.getValue()))
                .toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", title);
        snapshot.put("body", Map.of("schema_version", 1, "doc", Map.of("type", "doc", "content", blocks)));
        snapshot.put("properties", properties);
        snapshot.put("relations", relationList);
        mockMvc.perform(as(body(put("/files/" + document + "/content"), json.writeValueAsString(snapshot)))
                        .header(HttpHeaders.IF_MATCH, "\"" + revision + "\""))
                .andExpect(status().isOk());
    }

    private static Map<String, String> property(String key, String value) {
        return Map.of("key", key, "value", value);
    }
}
