package com.loresentry.content.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 한 프로젝트의 문서 여러 개를 분석용 평문으로 한 번에 읽는 {@code GET /projects/{id}/files/content}. */
class DocumentContentsApiTest extends ApiTestSupport {

    private UUID project;

    @BeforeEach
    void createProject() throws Exception {
        project = createProject(owner, "문서 묶음");
    }

    /** 활성 문서는 평문·속성·관계와 함께 오고, 열 수 없는 문서는 요청한 순서대로 missing 에 모인다. */
    @Test
    void readsTheActiveDocumentsAndListsTheRestAsMissing() throws Exception {
        UUID chapter = createDocument(project, "MANUSCRIPT", "1화");
        UUID character = createDocument(project, "CHARACTER", "서윤");
        mockMvc.perform(as(body(put("/files/" + character + "/content"), """
                        {"title":"서윤","body":{"schema_version":1,"doc":{"type":"doc","content":[
                          {"type":"paragraph","content":[{"type":"text","text":"기록관의 막내"}]}]}},
                         "properties":[{"key":"alias","value":"윤"}],
                         "relations":[{"relation_key":"related_manuscript","target_document_id":"%s",
                                       "description":"첫 등장"}]}
                        """.formatted(chapter))).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
        UUID trashed = createDocument(project, "ITEM", "버린 아이템");
        mockMvc.perform(as(post("/files/" + trashed + "/trash"))).andExpect(status().isNoContent());
        UUID elsewhere = createDocument(createProject(owner, "다른 프로젝트"), "MANUSCRIPT", "남의 1화");
        UUID unknown = UUID.randomUUID();

        MvcResult result = mockMvc.perform(as(contentsOf(project, trashed, character, elsewhere, chapter, unknown)))
                .andExpect(status().isOk())
                .andReturn();

        String chapterJson = """
                {"id":"%s","revision_no":0,"folder_code":"MANUSCRIPT","title":"1화","body_text":"",
                 "properties":[],
                 "relations":[{"relation_key":"related_character","target_document_id":"%s","description":"첫 등장"}]}
                """.formatted(chapter, character);
        String characterJson = """
                {"id":"%s","revision_no":1,"folder_code":"CHARACTER","title":"서윤","body_text":"기록관의 막내",
                 "properties":[{"key":"alias","value":"윤"}],
                 "relations":[{"relation_key":"related_manuscript","target_document_id":"%s","description":"첫 등장"}]}
                """.formatted(character, chapter);
        // 문서는 id 순(uuid 문자열 순 = PostgreSQL uuid 순)이다.
        String documents = chapter.toString().compareTo(character.toString()) < 0
                ? chapterJson + "," + characterJson : characterJson + "," + chapterJson;
        assertThat(read(result)).isEqualTo(json.readTree("""
                {"project_id":"%s","documents":[%s],"missing":["%s","%s","%s"]}
                """.formatted(project, documents, trashed, elsewhere, unknown)));
    }

    /** 같은 id 를 여러 번 보내도 한 번만 온다. */
    @Test
    void readsARepeatedIdOnce() throws Exception {
        UUID chapter = createDocument(project, "MANUSCRIPT", "1화");

        mockMvc.perform(as(contentsOf(project, chapter, chapter)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.missing.length()").value(0));
    }

    /** 1~100개만 받는다. 형식이 틀린 id 나 빈 목록은 잘못된 요청이다. */
    @Test
    void rejectsTooManyOrMalformedIds() throws Exception {
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(UUID.randomUUID());
        }
        mockMvc.perform(as(contentsOf(project, tooMany.toArray(UUID[]::new))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(as(get("/projects/" + project + "/files/content").param("ids", "not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(as(get("/projects/" + project + "/files/content")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /** 남의 프로젝트와 휴지통 프로젝트는 없는 것으로 답한다. */
    @Test
    void onlyTheOwnersActiveProjectCanBeRead() throws Exception {
        UUID chapter = createDocument(project, "MANUSCRIPT", "1화");

        mockMvc.perform(as(contentsOf(project, chapter), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        mockMvc.perform(as(post("/projects/" + project + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(contentsOf(project, chapter)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    private static MockHttpServletRequestBuilder contentsOf(UUID project, UUID... ids) {
        MockHttpServletRequestBuilder request = get("/projects/" + project + "/files/content");
        for (UUID id : ids) {
            request.param("ids", id.toString());
        }
        return request;
    }

    private UUID createProject(UUID user, String name) throws Exception {
        return idOf(mockMvc.perform(as(body(post("/projects"), "{\"name\":\"" + name + "\"}"), user))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private UUID createDocument(UUID inProject, String folderCode, String title) throws Exception {
        return idOf(mockMvc.perform(as(body(post("/projects/" + inProject + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode + "\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(read(result).get("id").stringValue());
    }
}
