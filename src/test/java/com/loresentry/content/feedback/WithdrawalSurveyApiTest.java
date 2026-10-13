package com.loresentry.content.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Array;
import java.util.List;
import java.util.Map;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WithdrawalSurveyApiTest extends ApiTestSupport {

    @BeforeEach
    void empty() {
        jdbcClient.sql("delete from withdrawal_survey").update();
    }

    private List<Map<String, Object>> rows() {
        return jdbcClient.sql("select * from withdrawal_survey").query().listOfRows();
    }

    private static List<Object> reasonsOf(Map<String, Object> row) throws Exception {
        return List.of((Object[]) ((Array) row.get("reasons")).getArray());
    }

    @Test
    void storesTheAnswerWithoutAnythingThatPointsBackToTheUser() throws Exception {
        mockMvc.perform(as(body(post("/withdrawal-surveys"), """
                        {"reasons":["rarely_used"," MISSING_FEATURE ","RARELY_USED"],"comment":"  타임라인이 더 필요해요  "}
                        """)))
                .andExpect(status().isNoContent());

        Map<String, Object> row = rows().getFirst();
        assertThat(row).containsOnlyKeys("id", "reasons", "comment", "created_at");
        assertThat(reasonsOf(row)).containsExactly("RARELY_USED", "MISSING_FEATURE");
        assertThat(row.get("comment")).isEqualTo("타임라인이 더 필요해요");
    }

    @Test
    void takesReasonsOrACommentAlone() throws Exception {
        mockMvc.perform(as(body(post("/withdrawal-surveys"), "{\"reasons\":[\"PAUSED_WRITING\"]}")))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(body(post("/withdrawal-surveys"), "{\"reasons\":[],\"comment\":\"잘 썼어요\"}")))
                .andExpect(status().isNoContent());

        assertThat(rows()).hasSize(2);
        assertThat(rows()).anySatisfy(row -> assertThat(row.get("comment")).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"reasons\":[\"BORED\"]}",
        "{\"reasons\":[null]}",
        "{\"reasons\":[],\"comment\":\"   \"}",
        "{}"})
    void rejectsAnUnknownReasonOrAnEmptyAnswer(String payload) throws Exception {
        mockMvc.perform(as(body(post("/withdrawal-surveys"), payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WITHDRAWAL_SURVEY"));

        assertThat(rows()).isEmpty();
    }

    @Test
    void rejectsAMissingBodyAsAnyOtherMalformedRequest() throws Exception {
        mockMvc.perform(as(body(post("/withdrawal-surveys"), "null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(rows()).isEmpty();
    }

    @Test
    void capsTheCommentAtAThousandCharacters() throws Exception {
        mockMvc.perform(as(body(post("/withdrawal-surveys"),
                        "{\"reasons\":[\"OTHER\"],\"comment\":\"" + "가".repeat(1000) + "\"}")))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(body(post("/withdrawal-surveys"),
                        "{\"reasons\":[\"OTHER\"],\"comment\":\"" + "가".repeat(1001) + "\"}")))
                .andExpect(status().isBadRequest());

        assertThat(rows()).hasSize(1);
    }

    @Test
    void outlivesTheDataPurgeOfTheAccountThatLeft() throws Exception {
        mockMvc.perform(as(body(post("/withdrawal-surveys"), "{\"reasons\":[\"PRIVACY\"]}")))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/users/me/data"))).andExpect(status().isNoContent());

        assertThat(rows()).hasSize(1);
    }

    @Test
    void needsTheUserContextLikeEveryOtherCall() throws Exception {
        mockMvc.perform(body(post("/withdrawal-surveys"), "{\"reasons\":[\"OTHER\"]}"))
                .andExpect(status().isUnauthorized());

        assertThat(rows()).isEmpty();
    }
}
