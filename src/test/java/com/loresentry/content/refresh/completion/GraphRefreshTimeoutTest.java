package com.loresentry.content.refresh.completion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent.Outcome;
import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent.Result;
import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/** 결과가 오지 않는 최신화는 시간 제한이 지나면 실패로 끝나고, 작업본·기준 버전·잠금이 치워진다. */
@TestPropertySource(properties = "graph-refresh.request-bucket=timeout-bucket")
class GraphRefreshTimeoutTest extends ApiTestSupport {

    @MockitoBean(name = "refreshS3Client")
    private S3Client s3;

    @Autowired
    private RefreshTimeout timeout;

    @Autowired
    private GraphRefreshCompletionService service;

    private UUID project;

    private UUID chapter;

    @BeforeEach
    void seed() throws Exception {
        willAnswer(invocation -> PutObjectResponse.builder().build())
                .given(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        project = idOf(mockMvc.perform(as(body(post("/projects"), "{\"name\":\"시간 제한\"}"))).andReturn());
        chapter = idOf(mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"MANUSCRIPT\",\"title\":\"1화\"}")))
                .andExpect(status().isCreated())
                .andReturn());
    }

    @Test
    void anOverdueRunFailsAndReleasesItsDocumentsWhileAFreshOneWaits() throws Exception {
        UUID overdue = requestRefresh();
        jdbcClient.sql("update refresh_runs set started_at = now() - interval '2 hours' where id = :id")
                .param("id", overdue).update();

        assertThat(timeout.expireOverdue()).isGreaterThanOrEqualTo(1);

        assertThat(jdbcClient.sql("select status || ':' || error_code from refresh_runs where id = :id")
                .param("id", overdue).query(String.class).single()).isEqualTo("FAILED:TIMED_OUT");
        assertThat(jdbcClient.sql("select count(*) from refresh_document_drafts where refresh_run_id = :id")
                .param("id", overdue).query(Long.class).single()).isZero();
        mockMvc.perform(as(get("/files/" + chapter + "/content")))
                .andExpect(jsonPath("$.locked").value(false));

        // 실패한 run 의 문서는 다시 실린다. 새 run 은 아직 시간 제한 전이라 그대로다.
        UUID fresh = requestRefresh();
        timeout.expireOverdue();
        assertThat(jdbcClient.sql("select status from refresh_runs where id = :id")
                .param("id", fresh).query(String.class).single()).isEqualTo("GENERATING");

        // 시간 제한으로 끝난 run 에 늦게 온 결과는 읽지도 않는다.
        assertThat(service.fetchResult(new GraphRefreshCompletedEvent(overdue, project, Outcome.SUCCEEDED,
                new Result("timeout-bucket", "refresh/x/result.json"), null, "v13")))
                .isInstanceOf(ResultFetch.Skip.class);
    }

    private UUID requestRefresh() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(read(result).get("request_id").stringValue());
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(read(result).get("id").stringValue());
    }
}
