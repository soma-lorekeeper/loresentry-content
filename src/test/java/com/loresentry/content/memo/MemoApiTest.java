package com.loresentry.content.memo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class MemoApiTest extends ApiTestSupport {

    private UUID project;

    private UUID document;

    @BeforeEach
    void seed() throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"메모가 있는 원고\"}")))
                .andReturn();
        this.project = UUID.fromString(read(created).get("id").stringValue());
        MvcResult file = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"CHARACTER\",\"title\":\"유중혁\"}")))
                .andReturn();
        this.document = UUID.fromString(read(file).get("id").stringValue());
    }

    private UUID memo(String payload) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/memos"), payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private List<String> bodies(String queryString) throws Exception {
        MvcResult result = mockMvc.perform(as(get("/projects/" + project + "/memos?" + queryString)))
                .andExpect(status().isOk())
                .andReturn();
        List<String> out = new ArrayList<>();
        for (JsonNode node : read(result).get("memos")) {
            out.add(node.get("body").stringValue());
        }
        return out;
    }

    @Test
    void keepsProjectAndFileMemosApart() throws Exception {
        memo("{\"scope\":\"project\",\"body\":\"작품 전체 메모\"}");
        memo("{\"scope\":\"file\",\"document_id\":\"" + document + "\",\"body\":\"이 캐릭터 메모\"}");

        assertThat(bodies("scope=project")).containsExactly("작품 전체 메모");
        assertThat(bodies("scope=file&document_id=" + document)).containsExactly("이 캐릭터 메모");
    }

    @Test
    void allowsSeveralMemosInBothScopes() throws Exception {
        // 요구사항 §6.1: 프로젝트 메모도 파일 메모도 여러 개다.
        memo("{\"scope\":\"project\",\"body\":\"하나\"}");
        memo("{\"scope\":\"project\",\"body\":\"둘\"}");
        memo("{\"scope\":\"file\",\"document_id\":\"" + document + "\",\"body\":\"셋\"}");
        memo("{\"scope\":\"file\",\"document_id\":\"" + document + "\",\"body\":\"넷\"}");

        assertThat(bodies("scope=project")).hasSize(2);
        assertThat(bodies("scope=file&document_id=" + document)).hasSize(2);
    }

    @Test
    void listsMostRecentlyEditedFirst() throws Exception {
        UUID first = memo("{\"scope\":\"project\",\"body\":\"먼저 쓴 것\"}");
        memo("{\"scope\":\"project\",\"body\":\"나중에 쓴 것\"}");
        assertThat(bodies("scope=project")).containsExactly("나중에 쓴 것", "먼저 쓴 것");

        mockMvc.perform(as(body(patch("/memos/" + first), "{\"body\":\"먼저 쓴 것을 고쳤다\"}")))
                .andExpect(status().isOk());

        assertThat(bodies("scope=project")).containsExactly("먼저 쓴 것을 고쳤다", "나중에 쓴 것");
    }

    @Test
    void acceptsAnEmptyMemoBecauseTheCardIsCreatedBeforeItIsWritten() throws Exception {
        mockMvc.perform(as(body(post("/projects/" + project + "/memos"),
                        "{\"scope\":\"project\",\"body\":\"\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value(""))
                .andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void updatesOnlyWhatWasSent() throws Exception {
        UUID id = memo("{\"scope\":\"project\",\"title\":\"제목\",\"body\":\"본문\"}");

        mockMvc.perform(as(body(patch("/memos/" + id), "{\"body\":\"본문만 고침\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("제목"))
                .andExpect(jsonPath("$.body").value("본문만 고침"));
    }

    @Test
    void refusesAFileMemoWithoutAUsableDocument() throws Exception {
        mockMvc.perform(as(body(post("/projects/" + project + "/memos"),
                        "{\"scope\":\"file\",\"body\":\"문서를 안 줬다\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEMO"));

        mockMvc.perform(as(body(post("/projects/" + project + "/memos"),
                        "{\"scope\":\"file\",\"document_id\":\"" + UUID.randomUUID()
                                + "\",\"body\":\"없는 문서\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEMO"));
    }

    @Test
    void refusesAMemoOnATrashedDocument() throws Exception {
        mockMvc.perform(as(post("/files/" + document + "/trash"))).andExpect(status().isNoContent());

        // 휴지통 문서에 메모를 달면 어디에도 보이지 않는 메모가 생긴다.
        mockMvc.perform(as(body(post("/projects/" + project + "/memos"),
                        "{\"scope\":\"file\",\"document_id\":\"" + document + "\",\"body\":\"x\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEMO"));
    }

    @Test
    void requiresAScope() throws Exception {
        mockMvc.perform(as(get("/projects/" + project + "/memos")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(get("/projects/" + project + "/memos?scope=nonsense")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEMO"));
        mockMvc.perform(as(get("/projects/" + project + "/memos?scope=file")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEMO"));
    }

    @Test
    void deletesAMemo() throws Exception {
        UUID id = memo("{\"scope\":\"project\",\"body\":\"지울 것\"}");

        mockMvc.perform(as(delete("/memos/" + id))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/memos/" + id)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMO_NOT_FOUND"));
        assertThat(bodies("scope=project")).isEmpty();
    }

    @Test
    void removesFileMemosWhenTheDocumentIsDeletedForGood() throws Exception {
        memo("{\"scope\":\"file\",\"document_id\":\"" + document + "\",\"body\":\"문서와 함께\"}");

        mockMvc.perform(as(post("/files/" + document + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/files/" + document))).andExpect(status().isNoContent());

        assertThat(jdbcClient.sql("select count(*) from memo where project_id = :project")
                .param("project", project).query(Long.class).single()).isZero();
    }

    @Test
    void hidesAnotherOwnersMemos() throws Exception {
        UUID id = memo("{\"scope\":\"project\",\"body\":\"내 메모\"}");

        mockMvc.perform(as(get("/projects/" + project + "/memos?scope=project"), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mockMvc.perform(as(body(patch("/memos/" + id), "{\"body\":\"뺏기\"}"), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEMO_NOT_FOUND"));
        mockMvc.perform(as(delete("/memos/" + id), stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void countsMemoWorkAsProjectActivity() throws Exception {
        mockMvc.perform(as(body(post("/projects"), "{\"name\":\"끼어드는 것\"}")))
                .andExpect(status().isCreated());

        memo("{\"scope\":\"project\",\"body\":\"메모를 쓰는 것도 작업이다\"}");

        MvcResult listing = mockMvc.perform(as(get("/projects"))).andReturn();
        assertThat(read(listing).get("projects").get(0).get("name").stringValue())
                .isEqualTo("메모가 있는 원고");
    }
}
