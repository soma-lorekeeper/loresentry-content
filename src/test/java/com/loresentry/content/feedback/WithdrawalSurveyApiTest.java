package com.loresentry.content.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Array;
import java.util.List;
import java.util.Map;

import com.loresentry.content.support.ApiTestSupport;
import com.loresentry.content.user.UserDeletedEvent;
import com.loresentry.content.user.UserDeletedService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class WithdrawalSurveyApiTest extends ApiTestSupport {

    /** 회원 탈퇴의 데이터 삭제. UserDeleted 를 받았을 때 inbox 가 부르는 곳이다. */
    @Autowired
    UserDeletedService userDeleted;

    /** inbox 의 InboxTransaction 처럼 트랜잭션을 열고 commit 한다. */
    @Autowired
    TransactionTemplate transaction;

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
        transaction.executeWithoutResult(status -> userDeleted.handle(new UserDeletedEvent(owner)));

        assertThat(rows()).hasSize(1);
    }

    @Test
    void needsTheUserContextLikeEveryOtherCall() throws Exception {
        mockMvc.perform(body(post("/withdrawal-surveys"), "{\"reasons\":[\"OTHER\"]}"))
                .andExpect(status().isUnauthorized());

        assertThat(rows()).isEmpty();
    }
}
