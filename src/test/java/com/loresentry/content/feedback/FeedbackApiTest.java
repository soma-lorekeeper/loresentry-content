package com.loresentry.content.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class FeedbackApiTest extends ApiTestSupport {

    private long rowsOf(UUID user) {
        return jdbcClient.sql("select count(*) from feedback where user_id = :u")
                .param("u", user).query(Long.class).single();
    }

    private void insertAt(UUID user, OffsetDateTime createdAt) {
        jdbcClient.sql("insert into feedback (user_id, category, message, created_at) "
                        + "values (:u, 'BUG', 'x', :at)")
                .param("u", user).param("at", createdAt).update();
    }

    @Test
    void storesTrimmedFeedbackAndAnswersIdAndTime() throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/feedback"), """
                        {"category":"BUG","message":"  저장이 안 돼요  ","page":"/projects/","client":"Mozilla/5.0"}
                        """)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.created_at").isString())
                .andReturn();
        JsonNode created = read(result);
        assertThat(created.propertyNames()).containsExactlyInAnyOrder("id", "created_at");

        Map<String, Object> row = jdbcClient.sql("select * from feedback where id = :id")
                .param("id", UUID.fromString(created.get("id").stringValue()))
                .query().singleRow();
        assertThat(row.get("user_id")).isEqualTo(owner);
        assertThat(row.get("category")).isEqualTo("BUG");
        assertThat(row.get("message")).isEqualTo("저장이 안 돼요");
        assertThat(row.get("page")).isEqualTo("/projects/");
        assertThat(row.get("client")).isEqualTo("Mozilla/5.0");
    }

    @Test
    void pageAndClientAreOptional() throws Exception {
        mockMvc.perform(as(body(post("/feedback"), "{\"category\":\"IDEA\",\"message\":\"좋아요\"}")))
                .andExpect(status().isCreated());
        mockMvc.perform(as(body(post("/feedback"),
                        "{\"category\":\"OTHER\",\"message\":\"기타\",\"page\":null,\"client\":null}")))
                .andExpect(status().isCreated());

        assertThat(rowsOf(owner)).isEqualTo(2);
    }

    @Test
    void truncatesLongPageAndClient() throws Exception {
        String page = "/" + "p".repeat(400);
        String client = "가".repeat(500);
        MvcResult result = mockMvc.perform(as(body(post("/feedback"),
                        "{\"category\":\"BUG\",\"message\":\"m\",\"page\":\"" + page
                                + "\",\"client\":\"" + client + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();

        Map<String, Object> row = jdbcClient.sql("select page, client from feedback where id = :id")
                .param("id", UUID.fromString(read(result).get("id").stringValue()))
                .query().singleRow();
        assertThat(row.get("page")).isEqualTo(page.substring(0, 200));
        assertThat(row.get("client")).isEqualTo("가".repeat(300));
    }

    @Test
    void rejectsUnknownCategoryAndBlankOrLongMessage() throws Exception {
        for (String payload : new String[] {
                "{\"category\":\"PRAISE\",\"message\":\"m\"}",
                "{\"message\":\"m\"}",
                "{\"category\":\"BUG\",\"message\":\"   \"}",
                "{\"category\":\"BUG\"}",
                "{\"category\":\"BUG\",\"message\":\"" + "a".repeat(2001) + "\"}"}) {
            mockMvc.perform(as(body(post("/feedback"), payload)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_FEEDBACK"))
                    .andExpect(jsonPath("$.next_action").value("NONE"));
        }
        mockMvc.perform(as(body(post("/feedback"),
                        "{\"category\":\"BUG\",\"message\":\"  " + "a".repeat(2000) + "  \"}")))
                .andExpect(status().isCreated());

        assertThat(rowsOf(owner)).isEqualTo(1);
    }

    @Test
    void rejectsMalformedJsonAndUnknownFields() throws Exception {
        mockMvc.perform(as(body(post("/feedback"), "{\"category\":")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(as(body(post("/feedback"),
                        "{\"category\":\"BUG\",\"message\":\"m\",\"email\":\"a@b.c\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(rowsOf(owner)).isZero();
    }

    @Test
    void needsTheCallersIdentity() throws Exception {
        mockMvc.perform(body(post("/feedback"), "{\"category\":\"BUG\",\"message\":\"m\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"));
    }

    @Test
    void limitsTwentyPerUserPerHour() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < 5; i++) {
            insertAt(owner, now.minusHours(2));
        }
        for (int i = 0; i < 19; i++) {
            insertAt(owner, now.minusMinutes(30));
        }

        mockMvc.perform(as(body(post("/feedback"), "{\"category\":\"BUG\",\"message\":\"스무 번째\"}")))
                .andExpect(status().isCreated());
        mockMvc.perform(as(body(post("/feedback"), "{\"category\":\"BUG\",\"message\":\"스물한 번째\"}")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("FEEDBACK_RATE_LIMITED"))
                .andExpect(jsonPath("$.next_action").value("RETRY_LATER"));
        mockMvc.perform(as(body(post("/feedback"), "{\"category\":\"BUG\",\"message\":\"다른 사람\"}"), stranger))
                .andExpect(status().isCreated());

        assertThat(rowsOf(owner)).isEqualTo(25);
    }
}
