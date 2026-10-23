package com.loresentry.content.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 검토를 마친 최신화의 확정. 원고와 유나를 실어 요청하고, 유나 본문 수정과 새 아이템 "은색 검"(유나와 관계)을 제안받은
 * run 을 확정한다. 모든 작업본을 맞춰야 확정되고, 확정은 한 트랜잭션으로 문서·관계·버전·이벤트를 남긴다.
 */
class GraphRefreshConfirmApiTest extends RefreshScenario {

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
    void draftsMustAllBeMatchedFirst() throws Exception {
        mockMvc.perform(as(post(confirmPath())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_DRAFTS_UNMATCHED"))
                .andExpect(jsonPath("$.drafts.length()").value(2));
        assertThat(runStatus()).isEqualTo("READY");
    }

    @Test
    void acceptingEverythingWritesDocumentsRelationsVersionsAndEvents() throws Exception {
        long heroRevision = revisionOf(hero);
        long eventsBefore = fileChangedCount();
        JsonNode drafts = drafts();
        accept(drafts.get(0));
        accept(drafts.get(1));

        JsonNode confirmed = read(mockMvc.perform(as(post(confirmPath())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andReturn());

        UUID sword = UUID.fromString(confirmed.at("/created/0/file_id").stringValue());
        assertThat(confirmed.at("/created/0/candidate_id").stringValue()).isEqualTo("new:ITEM:은색 검");
        assertThat(confirmed.at("/updated/0/file_id").stringValue()).isEqualTo(hero.toString());
        assertThat(confirmed.at("/updated/0/revision_no").asLong()).isEqualTo(heroRevision + 1);
        assertThat(runStatus()).isEqualTo("APPLIED");

        // 유나: 고친 본문, 새 아이템과의 관계. 잠금이 풀렸고 AI_APPLY 버전이 남았다.
        JsonNode heroNow = content(hero);
        assertThat(heroNow.at("/body/doc/content/1/content/0/text").stringValue()).isEqualTo("은색 검을 든다.");
        assertThat(heroNow.get("relations").findValuesAsString("target_document_id"))
                .contains(sword.toString(), chapter.toString());
        assertThat(heroNow.get("locked").booleanValue()).isFalse();
        assertThat(content(chapter).get("locked").booleanValue()).isFalse();
        mockMvc.perform(as(get("/files/" + hero + "/versions")))
                .andExpect(jsonPath("$.versions[?(@.kind == 'AI_APPLY')]").isNotEmpty());

        // 은색 검: 아이템 폴더에 만들어졌고 본문과 유나와의 관계를 갖는다.
        JsonNode swordNow = content(sword);
        assertThat(swordNow.get("title").stringValue()).isEqualTo("은색 검");
        assertThat(swordNow.get("folder_code").stringValue()).isEqualTo("ITEM");
        assertThat(swordNow.at("/body/doc/content/0/content/0/text").stringValue()).isEqualTo("유나의 검이다.");
        assertThat(swordNow.at("/relations/0/target_document_id").stringValue()).isEqualTo(hero.toString());

        // 이벤트: 새 문서는 CREATED, 유나는 본문이 바뀐 UPDATED.
        List<JsonNode> events = fileChangedSince(eventsBefore);
        assertThat(events).anySatisfy(event -> {
            assertThat(event.at("/file_id").stringValue()).isEqualTo(sword.toString());
            assertThat(event.get("change_type").stringValue()).isEqualTo("CREATED");
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.at("/file_id").stringValue()).isEqualTo(hero.toString());
            assertThat(event.get("changed_fields").toString()).contains("BODY");
        });

        // 작업본·기준 버전이 치워졌고, 다음 최신화를 할 수 있다.
        assertThat(jdbcClient.sql("select count(*) from refresh_document_drafts where refresh_run_id = :run")
                .param("run", run).query(Long.class).single()).isZero();
        requestRefresh();
    }

    @Test
    void rejectingEverythingChangesNothing() throws Exception {
        long eventsBefore = fileChangedCount();
        JsonNode drafts = drafts();
        reject(drafts.get(0));
        mockMvc.perform(as(body(patch(draftPath(drafts.get(1))),
                        "{\"draft_revision\":0,\"left_snapshot\":null,\"right_snapshot\":null}")))
                .andExpect(status().isOk());

        mockMvc.perform(as(post(confirmPath())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created.length()").value(0))
                .andExpect(jsonPath("$.updated.length()").value(0))
                .andExpect(jsonPath("$.unchanged.length()").value(2));

        assertThat(content(hero).at("/body/doc/content").size()).isEqualTo(1);
        assertThat(fileChangedSince(eventsBefore)).isEmpty();
        assertThat(content(hero).get("locked").booleanValue()).isFalse();
    }

    /** 검토 중 잠기지 않은 다른 문서가 유나와 관계를 맺어도, 확정은 그 관계를 지우지 않는다(쌍 단위로만 바꾼다). */
    @Test
    void aRelationAddedElsewhereDuringReviewSurvives() throws Exception {
        JsonNode drafts = drafts();
        UUID friend = createDocument("CHARACTER", "도윤");
        save(friend, "도윤", "친구", "[{\"relation_key\":\"related_character\",\"target_document_id\":\"" + hero
                + "\",\"description\":\"\"}]");
        accept(drafts.get(0));
        accept(drafts.get(1));

        mockMvc.perform(as(post(confirmPath()))).andExpect(status().isOk());

        assertThat(content(hero).get("relations").findValuesAsString("target_document_id"))
                .contains(friend.toString());
    }

    @Test
    void aTitleTakenDuringReviewRollsBackTheWholeConfirm() throws Exception {
        JsonNode drafts = drafts();
        createDocument("ITEM", "은색 검");
        accept(drafts.get(0));
        accept(drafts.get(1));

        mockMvc.perform(as(post(confirmPath())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_TITLE_TAKEN"));

        assertThat(runStatus()).isEqualTo("READY");
        assertThat(content(hero).at("/body/doc/content").size()).isEqualTo(1);
        assertThat(content(hero).get("refresh_locked").booleanValue()).isTrue();
    }

    @Test
    void aRelationToADeclinedNewDocumentCannotBeApplied() throws Exception {
        JsonNode drafts = drafts();
        accept(drafts.get(0));
        mockMvc.perform(as(body(patch(draftPath(drafts.get(1))),
                        "{\"draft_revision\":0,\"left_snapshot\":null,\"right_snapshot\":null}")))
                .andExpect(status().isOk());

        mockMvc.perform(as(post(confirmPath())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_DRAFTS_INVALID"))
                .andExpect(jsonPath("$.problems[0].code").value("REJECTED_CANDIDATE"));
    }

    @Test
    void onlyTheOwnerConfirmsAReadyRun() throws Exception {
        mockMvc.perform(as(post(confirmPath()), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        jdbcClient.sql("update refresh_runs set status = 'GENERATING' where id = :id").param("id", run).update();
        mockMvc.perform(as(post(confirmPath())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_NOT_READY"));
    }

    // --- 도우미 -------------------------------------------------------------------------------------------------

    /** 제안을 받아들인다: left 를 right 와 같게 저장한다. */
    private void accept(JsonNode draft) throws Exception {
        ObjectNode save = json.createObjectNode().put("draft_revision", draft.get("draft_revision").asLong());
        save.set("left_snapshot", draft.get("right_snapshot"));
        save.set("right_snapshot", draft.get("right_snapshot"));
        mockMvc.perform(as(body(patch(draftPath(draft)), save.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true));
    }

    /** 제안을 거절한다: right 를 left 와 같게 저장한다. */
    private void reject(JsonNode draft) throws Exception {
        ObjectNode save = json.createObjectNode().put("draft_revision", draft.get("draft_revision").asLong());
        save.set("left_snapshot", draft.get("left_snapshot"));
        save.set("right_snapshot", draft.get("left_snapshot"));
        mockMvc.perform(as(body(patch(draftPath(draft)), save.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true));
    }

    private JsonNode drafts() throws Exception {
        return read(mockMvc.perform(as(get("/projects/" + project + "/graph-refresh/" + run))).andReturn())
                .get("drafts");
    }

    private JsonNode content(UUID file) throws Exception {
        return read(mockMvc.perform(as(get("/files/" + file + "/content"))).andExpect(status().isOk()).andReturn());
    }

    private String draftPath(JsonNode draft) {
        return "/projects/" + project + "/graph-refresh/" + run + "/drafts/" + draft.get("id").stringValue();
    }

    private String confirmPath() {
        return "/projects/" + project + "/graph-refresh/" + run + "/confirm";
    }

    private String runStatus() {
        return jdbcClient.sql("select status from refresh_runs where id = :id")
                .param("id", run).query(String.class).single();
    }

    private long fileChangedCount() {
        return jdbcClient.sql("select coalesce(max(seq), 0) from outbox_events "
                        + "where event_type = 'FileChanged' and event_key = :project")
                .param("project", project.toString()).query(Long.class).single();
    }

    private List<JsonNode> fileChangedSince(long seq) {
        return jdbcClient.sql("select payload::text from outbox_events where event_type = 'FileChanged' "
                        + "and event_key = :project and seq > :seq order by seq")
                .param("project", project.toString()).param("seq", seq)
                .query((rows, index) -> json.readTree(rows.getString(1)))
                .list();
    }
}
