package com.loresentry.content.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class SearchApiTest extends ApiTestSupport {

    private UUID project;

    @BeforeEach
    void seed() throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"유리 정원의 기록\"}")))
                .andReturn();
        this.project = UUID.fromString(read(created).get("id").stringValue());
    }

    private UUID document(String folderCode, String title, String bodyMd) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode + "\",\"title\":\""
                                + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(read(result).get("id").stringValue());

        if (bodyMd != null) {
            mockMvc.perform(as(body(put("/files/" + id + "/content"),
                            json.writeValueAsString(java.util.Map.of("title", title, "body_md", bodyMd))))
                            .header(HttpHeaders.IF_MATCH, "\"0\""))
                    .andExpect(status().isOk());
        }
        return id;
    }

    private MvcResult searchFor(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        return mockMvc.perform(as(get(URI.create("/projects/" + project + "/search?q=" + encoded))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private List<String> titlesFor(String query) throws Exception {
        List<String> titles = new ArrayList<>();
        for (JsonNode hit : read(searchFor(query)).get("hits")) {
            titles.add(hit.get("title").stringValue());
        }
        return titles;
    }

    @Test
    void findsTitlesAndBodies() throws Exception {
        document("CHARACTER", "유중혁", "회귀를 반복하는 인물이다.");
        document("LOCATION", "지하철", "유중혁이 처음 눈을 뜨는 곳이다.");
        document("ITEM", "무관한 것", "여기에는 없다.");

        assertThat(titlesFor("유중혁")).containsExactly("유중혁", "지하철");
    }

    @Test
    void ranksAnExactTitleAboveAPartialTitleAboveTheBody() throws Exception {
        document("CHARACTER", "회귀", "본문에는 없음");
        document("LOCATION", "회귀의 탑", "본문에는 없음");
        document("ITEM", "무관한 제목", "여기 본문에 회귀가 있다.");

        assertThat(titlesFor("회귀")).containsExactly("회귀", "회귀의 탑", "무관한 제목");
    }

    @Test
    void ignoresCase() throws Exception {
        document("CHARACTER", "Glass Garden", "A greenhouse story.");

        assertThat(titlesFor("glass")).containsExactly("Glass Garden");
        assertThat(titlesFor("GREENHOUSE")).containsExactly("Glass Garden");
    }

    @Test
    void cutsASnippetAroundTheMatch() throws Exception {
        document("CHARACTER", "유중혁", "앞쪽 문장이다. 회귀를 반복한다. 뒤쪽 문장이다.");

        mockMvc.perform(as(get(URI.create("/projects/" + project + "/search?q="
                        + URLEncoder.encode("회귀", StandardCharsets.UTF_8)))))
                .andExpect(jsonPath("$.hits[0].snippet.match").value("회귀"))
                .andExpect(jsonPath("$.hits[0].snippet.before").value(
                        org.hamcrest.Matchers.containsString("앞쪽 문장이다.")))
                .andExpect(jsonPath("$.hits[0].snippet.after").value(
                        org.hamcrest.Matchers.containsString("반복한다")));
    }

    @Test
    void leavesTheSnippetEmptyWhenOnlyTheTitleMatched() throws Exception {
        document("CHARACTER", "유중혁", "본문에는 그 이름이 없다.");

        mockMvc.perform(as(get(URI.create("/projects/" + project + "/search?q="
                        + URLEncoder.encode("유중혁", StandardCharsets.UTF_8)))))
                .andExpect(jsonPath("$.hits[0].snippet").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void reportsWhereTheHitLives() throws Exception {
        document("CHARACTER", "유중혁", null);

        mockMvc.perform(as(get(URI.create("/projects/" + project + "/search?q="
                        + URLEncoder.encode("유중혁", StandardCharsets.UTF_8)))))
                .andExpect(jsonPath("$.hits[0].folder_code").value("CHARACTER"))
                .andExpect(jsonPath("$.hits[0].episode_name").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void leavesTrashedDocumentsOut() throws Exception {
        UUID trashed = document("CHARACTER", "유중혁", "회귀를 반복한다.");
        mockMvc.perform(as(post("/files/" + trashed + "/trash"))).andExpect(status().isNoContent());

        assertThat(titlesFor("유중혁")).isEmpty();
        assertThat(titlesFor("회귀")).isEmpty();
    }

    @Test
    void staysInsideTheProject() throws Exception {
        document("CHARACTER", "유중혁", null);
        MvcResult other = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"다른 프로젝트\"}")))
                .andReturn();
        UUID otherProject = UUID.fromString(read(other).get("id").stringValue());

        mockMvc.perform(as(get(URI.create("/projects/" + otherProject + "/search?q="
                        + URLEncoder.encode("유중혁", StandardCharsets.UTF_8)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits.length()").value(0));
    }

    @Test
    void treatsAnEmptyQueryAsNoResults() throws Exception {
        document("CHARACTER", "유중혁", null);

        mockMvc.perform(as(get("/projects/" + project + "/search")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits.length()").value(0));
        assertThat(titlesFor("   ")).isEmpty();
    }

    @Test
    void hidesAnotherOwnersProject() throws Exception {
        mockMvc.perform(as(get(URI.create("/projects/" + project + "/search?q=x")), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }
}
