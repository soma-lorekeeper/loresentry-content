package com.loresentry.content.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** 프로젝트 목록의 "최근 작업 순"과 "마지막으로 작업한 파일"(요구사항 §2.2). */
class LastWorkedTest extends ApiTestSupport {

    private UUID project(String name) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"" + name + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private UUID document(UUID projectId, String title) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + projectId + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"CHARACTER\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private List<String> projectOrder() throws Exception {
        MvcResult result = mockMvc.perform(as(get("/projects"))).andExpect(status().isOk()).andReturn();
        List<String> names = new ArrayList<>();
        for (JsonNode node : read(result).get("projects")) {
            names.add(node.get("name").stringValue());
        }
        return names;
    }

    private JsonNode projectNode(UUID projectId) throws Exception {
        return read(mockMvc.perform(as(get("/projects/" + projectId))).andReturn());
    }

    @Test
    void reportsNoLastFileWhileTheProjectIsEmpty() throws Exception {
        UUID projectId = project("빈 프로젝트");

        mockMvc.perform(as(get("/projects/" + projectId)))
                .andExpect(jsonPath("$.last_file").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void reportsTheMostRecentlyEditedDocument() throws Exception {
        UUID projectId = project("유리 정원의 기록");
        document(projectId, "유중혁");
        UUID second = document(projectId, "김독자");

        JsonNode node = projectNode(projectId);
        assertThat(node.get("last_file").get("id").stringValue()).isEqualTo(second.toString());
        assertThat(node.get("last_file").get("title").stringValue()).isEqualTo("김독자");
    }

    @Test
    void followsTheEditRatherThanTheCreationOrder() throws Exception {
        UUID projectId = project("유리 정원의 기록");
        UUID first = document(projectId, "유중혁");
        document(projectId, "김독자");

        mockMvc.perform(as(body(put("/files/" + first + "/content"),
                        "{\"title\":\"유중혁\",\"body_md\":\"고쳤다\"}"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        assertThat(projectNode(projectId).get("last_file").get("title").stringValue()).isEqualTo("유중혁");
    }

    @Test
    void leavesTrashedDocumentsOutOfTheLastFile() throws Exception {
        UUID projectId = project("유리 정원의 기록");
        document(projectId, "유중혁");
        UUID second = document(projectId, "김독자");

        mockMvc.perform(as(post("/files/" + second + "/trash"))).andExpect(status().isNoContent());

        // 휴지통 문서는 열 수 없으므로 "마지막으로 작업한 파일"로 보여 주면 막힌 링크가 된다.
        assertThat(projectNode(projectId).get("last_file").get("title").stringValue()).isEqualTo("유중혁");
    }

    @Test
    void carriesTheLastFileInBothListings() throws Exception {
        UUID projectId = project("유리 정원의 기록");
        document(projectId, "유중혁");

        mockMvc.perform(as(get("/projects")))
                .andExpect(jsonPath("$.projects[0].last_file.title").value("유중혁"));

        mockMvc.perform(as(post("/projects/" + projectId + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(get("/projects/trash")))
                .andExpect(jsonPath("$.projects[0].last_file.title").value("유중혁"));
    }

    @Test
    void movesAProjectToTheTopWhenADocumentIsEdited() throws Exception {
        UUID older = project("먼저 만든 것");
        UUID document = document(older, "유중혁");
        project("나중에 만든 것");
        assertThat(projectOrder()).containsExactly("나중에 만든 것", "먼저 만든 것");

        // 문서를 쓴 것이 곧 프로젝트를 작업한 것이다. 이것이 없으면 원고를 한 시간 써도
        // 이름만 바꾼 다른 프로젝트가 목록 위에 남는다.
        mockMvc.perform(as(body(put("/files/" + document + "/content"),
                        "{\"title\":\"유중혁\",\"body_md\":\"한 시간 썼다\"}"))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        assertThat(projectOrder()).containsExactly("먼저 만든 것", "나중에 만든 것");
    }

    @Test
    void countsEveryKindOfFileWorkAsProjectActivity() throws Exception {
        UUID worked = project("작업할 것");
        UUID document = document(worked, "유중혁");

        for (String action : List.of("rename", "move", "lock", "trash", "restore", "episode")) {
            // 매번 다른 프로젝트를 만들어 위로 올린 뒤, 해당 동작으로 다시 내려오는지 본다.
            project("끼어드는 것 " + action);
            assertThat(projectOrder().getFirst()).isEqualTo("끼어드는 것 " + action);

            switch (action) {
                case "rename" -> mockMvc.perform(as(body(patch("/files/" + document),
                        "{\"title\":\"유중혁 " + action + "\"}"))).andExpect(status().isOk());
                case "move" -> mockMvc.perform(as(body(patch("/files/" + document + "/position"),
                        "{\"folder_code\":\"CHARACTER\"}"))).andExpect(status().isOk());
                case "lock" -> mockMvc.perform(as(body(put("/files/" + document + "/lock"),
                        "{\"locked\":false}"))).andExpect(status().isOk());
                case "trash" -> mockMvc.perform(as(post("/files/" + document + "/trash")))
                        .andExpect(status().isNoContent());
                case "restore" -> mockMvc.perform(as(post("/files/" + document + "/restore")))
                        .andExpect(status().isOk());
                case "episode" -> mockMvc.perform(as(body(post("/projects/" + worked + "/files"),
                        "{\"kind\":\"episode\",\"title\":\"1부\"}"))).andExpect(status().isCreated());
                default -> throw new IllegalStateException(action);
            }

            assertThat(projectOrder().getFirst()).as("after %s", action).isEqualTo("작업할 것");
        }
    }
}
