package com.loresentry.content.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 결과를 기다리는 동안의 폴링, READY 가 된 뒤의 검토 화면 조회, 작업본 자동 저장. 검토할 run 은 원고와 유나를 실어
 * 요청한 뒤, 유나 본문 수정과 새 아이템 "은색 검"(유나와 관계)을 제안받아 만든다.
 */
class GraphRefreshReviewApiTest extends RefreshScenario {

    private UUID chapter;

    private UUID hero;

    private UUID run;

    @BeforeEach
    void openAReview() throws Exception {
        chapter = createDocument("MANUSCRIPT", "1화");
        hero = createDocument("CHARACTER", "유나");
        save(hero, "유나", "파란 망토를 입는다.", "[{\"relation_key\":\"related_manuscript\",\"target_document_id\":\""
                + chapter + "\",\"description\":\"첫 등장\"}]");
        run = requestRefresh();

        mockMvc.perform(as(get("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request_id").value(run.toString()))
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andExpect(jsonPath("$.draft_counts.total").value(0));

        complete(run, "PROPOSED", """
                "document_proposals":[{"target_document_id":"%s","base_revision_no":%d,"field":"body_text",
                  "value":"파란 망토를 입는다.\\n은색 검을 든다."}],
                "new_document_proposals":[{"candidate_id":"new:ITEM:은색 검","folder_code":"ITEM","name":"은색 검",
                  "body_text":"유나의 검이다."}],
                "relation_proposals":[{"document_id":"%s","target_document_id":"new:ITEM:은색 검",
                  "base_revision_no":%d,"target_base_revision_no":null,"description":"유나의 검","operation":"ADD"}]
                """.formatted(hero, revisionOf(hero), hero, revisionOf(hero)));
    }

    @Test
    void pollingShowsTheReadyRunAndItsDraftCounts() throws Exception {
        mockMvc.perform(as(get("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.ready_at").isNotEmpty())
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.draft_counts.total").value(2))
                .andExpect(jsonPath("$.draft_counts.matched").value(0))
                .andExpect(jsonPath("$.draft_counts.new").value(1));
    }

    @Test
    void detailShowsEachDraftWithItsSides() throws Exception {
        JsonNode detail = read(mockMvc.perform(as(get("/projects/" + project + "/graph-refresh/" + run)))
                .andExpect(status().isOk())
                .andReturn());

        JsonNode edit = detail.get("drafts").get(0);
        assertThat(edit.get("kind").stringValue()).isEqualTo("EDIT");
        assertThat(edit.at("/document/id").stringValue()).isEqualTo(hero.toString());
        assertThat(edit.at("/document/folder_code").stringValue()).isEqualTo("CHARACTER");
        assertThat(edit.get("base_snapshot").get("title").stringValue()).isEqualTo("유나");
        assertThat(edit.get("base_mismatch").booleanValue()).isFalse();
        assertThat(edit.at("/left_snapshot/body/doc/content").size()).isEqualTo(1);
        assertThat(edit.at("/right_snapshot/body/doc/content").size()).isEqualTo(2);
        assertThat(edit.get("candidate").isNull()).isTrue();

        JsonNode created = detail.get("drafts").get(1);
        assertThat(created.get("kind").stringValue()).isEqualTo("NEW");
        assertThat(created.at("/candidate/name").stringValue()).isEqualTo("은색 검");
        assertThat(created.at("/candidate/folder_code").stringValue()).isEqualTo("ITEM");
        assertThat(created.get("left_snapshot").isNull()).isTrue();
        assertThat(created.at("/right_snapshot/relations/0/target_document_id").stringValue())
                .isEqualTo(hero.toString());
        // 근거·사실 같은 결과 원본은 내보내지 않는다.
        assertThat(detail.toString()).doesNotContain("evidence", "facts");
    }

    @Test
    void savingBothSidesTheSameMatchesTheDraft() throws Exception {
        JsonNode edit = drafts().get(0);
        ObjectNode save = json.createObjectNode();
        save.put("draft_revision", 0);
        save.set("left_snapshot", edit.get("right_snapshot"));
        save.set("right_snapshot", edit.get("right_snapshot"));

        mockMvc.perform(as(body(patch(draftPath(edit)), save.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft_revision").value(1))
                .andExpect(jsonPath("$.matched").value(true));

        // 같은 번호로 다시 저장하면 다른 곳에서 먼저 저장한 것이다. 지금 모습을 돌려준다.
        mockMvc.perform(as(body(patch(draftPath(edit)), save.toString())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_DRAFT_CONFLICT"))
                .andExpect(jsonPath("$.current.draft_revision").value(1))
                .andExpect(jsonPath("$.current.matched").value(true));

        mockMvc.perform(as(get("/projects/" + project + "/graph-refresh")))
                .andExpect(jsonPath("$.draft_counts.matched").value(1));
    }

    @Test
    void aNewDraftCanBeEmptiedOnBothSidesToDeclineIt() throws Exception {
        JsonNode created = drafts().get(1);

        mockMvc.perform(as(body(patch(draftPath(created)),
                        "{\"draft_revision\":0,\"left_snapshot\":null,\"right_snapshot\":null}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true));
    }

    @Test
    void savingChecksTheSnapshots() throws Exception {
        JsonNode edit = drafts().get(0);
        mockMvc.perform(as(body(patch(draftPath(edit)),
                        "{\"draft_revision\":0,\"left_snapshot\":null,\"right_snapshot\":null}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        for (String target : new String[] { UUID.randomUUID().toString(), hero.toString() }) {
            ObjectNode right = (ObjectNode) edit.get("right_snapshot").deepCopy();
            right.putArray("relations").addObject().put("relation_key", "related_character")
                    .put("target_document_id", target).putNull("target_candidate_id").put("description", "");
            ObjectNode save = json.createObjectNode().put("draft_revision", 0);
            save.set("left_snapshot", edit.get("left_snapshot"));
            save.set("right_snapshot", right);
            mockMvc.perform(as(body(patch(draftPath(edit)), save.toString())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_RELATION_TARGET"));
        }

        // 관계 키는 서버가 대상 분류에서 다시 정한다.
        ObjectNode right = (ObjectNode) edit.get("right_snapshot").deepCopy();
        ((ObjectNode) right.get("relations").get(0)).put("relation_key", "anything");
        ObjectNode save = json.createObjectNode().put("draft_revision", 0);
        save.set("left_snapshot", edit.get("left_snapshot"));
        save.set("right_snapshot", right);
        mockMvc.perform(as(body(patch(draftPath(edit)), save.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.right_snapshot.relations[0].relation_key").value("related_item"));
    }

    @Test
    void onlyTheOwnerSeesTheRunAndOnlyAReadyRunTakesDrafts() throws Exception {
        mockMvc.perform(as(get("/projects/" + project + "/graph-refresh"), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mockMvc.perform(as(get("/projects/" + project + "/graph-refresh/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFRESH_NOT_FOUND"));

        JsonNode edit = drafts().get(0);
        jdbcClient.sql("update refresh_runs set status = 'GENERATING' where id = :id").param("id", run).update();
        mockMvc.perform(as(body(patch(draftPath(edit)), "{\"draft_revision\":0,\"left_snapshot\":"
                        + edit.get("left_snapshot") + ",\"right_snapshot\":" + edit.get("left_snapshot") + "}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_NOT_READY"));

        UUID untouched = idOf(mockMvc.perform(as(body(post("/projects"), "{\"name\":\"최신화 없음\"}"))).andReturn());
        mockMvc.perform(as(get("/projects/" + untouched + "/graph-refresh")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFRESH_NOT_FOUND"));
    }

    private JsonNode drafts() throws Exception {
        return read(mockMvc.perform(as(get("/projects/" + project + "/graph-refresh/" + run))).andReturn())
                .get("drafts");
    }

    private String draftPath(JsonNode draft) {
        return "/projects/" + project + "/graph-refresh/" + run + "/drafts/" + draft.get("id").stringValue();
    }
}
