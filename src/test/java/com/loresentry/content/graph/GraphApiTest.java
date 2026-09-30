package com.loresentry.content.graph;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

class GraphApiTest extends ApiTestSupport {

    private UUID project;

    @BeforeEach
    void seed() throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"관계가 있는 원고\"}")))
                .andReturn();
        this.project = UUID.fromString(read(created).get("id").stringValue());
    }

    private UUID document(String folderCode, String title) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode
                                + "\",\"title\":\"" + title + "\"}")))
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    @Test
    void projectsRelationsFromTheDatabaseWithoutGraphRag() throws Exception {
        // 타임라인과 관계도가 필요한 것은 전부 RDB 세 표에 있다. 한 홉이라 graph-rag 를 기다릴 이유가 없다.
        UUID chapter = document("MANUSCRIPT", "1화 회귀");
        UUID character = document("CHARACTER", "유중혁");

        mockMvc.perform(as(body(put("/files/" + chapter + "/content"), """
                {"title":"1화 회귀","body_md":"본문","properties":[],
                 "relations":[{"relation_key":"related_character","target_document_id":"%s",
                               "description":"이 회차에서 처음 등장한다"}]}
                """.formatted(character)))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        mockMvc.perform(as(get("/projects/" + project + "/graph")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andExpect(jsonPath("$.edges.length()").value(2))
                .andExpect(jsonPath("$.episodes.length()").value(0));
    }

    @Test
    void carriesEachRelationsOwnDescription() throws Exception {
        UUID chapter = document("MANUSCRIPT", "1화 회귀");
        UUID character = document("CHARACTER", "유중혁");

        mockMvc.perform(as(body(put("/files/" + chapter + "/content"), """
                {"title":"1화 회귀","body_md":"","properties":[],
                 "relations":[{"relation_key":"related_character","target_document_id":"%s",
                               "description":"동료가 되기 전"}]}
                """.formatted(character)))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());

        // 설명은 대상 문서의 것이 아니라 연결의 것이므로 양쪽에서 같아야 한다.
        mockMvc.perform(as(get("/files/" + chapter + "/content")))
                .andExpect(jsonPath("$.relations[0].description").value("동료가 되기 전"));
        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(jsonPath("$.relations[0].description").value("동료가 되기 전"));
    }

    @Test
    void listsEpisodesWithTheirChaptersInOrder() throws Exception {
        MvcResult episode = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"episode\",\"title\":\"1부\"}")))
                .andReturn();
        UUID episodeId = UUID.fromString(read(episode).get("id").stringValue());

        mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"MANUSCRIPT\",\"title\":\"1화\","
                                + "\"episode_id\":\"" + episodeId + "\"}")))
                .andExpect(status().isCreated());

        mockMvc.perform(as(get("/projects/" + project + "/graph")))
                .andExpect(jsonPath("$.episodes.length()").value(1))
                .andExpect(jsonPath("$.episodes[0].name").value("1부"))
                .andExpect(jsonPath("$.episodes[0].document_ids.length()").value(1));
    }

    @Test
    void refusesAProjectSomebodyElseOwns() throws Exception {
        mockMvc.perform(as(get("/projects/" + project + "/graph"), UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }
}
