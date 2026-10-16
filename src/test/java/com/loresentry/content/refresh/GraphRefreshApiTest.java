package com.loresentry.content.refresh;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@TestPropertySource(
        properties = {"graph-refresh.enabled=true", "graph-refresh.bucket=refresh-private-test"})
class GraphRefreshApiTest extends ApiTestSupport {
    @MockitoBean S3Client s3;
    @Autowired GraphRefreshCompletedService completed;
    @Autowired TransactionTemplate transaction;
    @Autowired GraphRefreshTimeouts timeouts;

    private UUID project() throws Exception {
        return UUID.fromString(
                read(mockMvc.perform(as(body(post("/projects"), "{\"name\":\"Kafka test\"}")))
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asString());
    }

    private UUID file(UUID project) throws Exception {
        return UUID.fromString(
                read(mockMvc.perform(
                                        as(
                                                body(
                                                        post("/projects/" + project + "/files"),
                                                        "{\"kind\":\"document\",\"folder_code\":\"MANUSCRIPT\",\"title\":\"원고\"}")))
                                .andExpect(status().isCreated())
                                .andReturn())
                        .get("id")
                        .asString());
    }

    @Test
    void domainEventsRequestAndCompletionAreDurableAndIdempotent() throws Exception {
        UUID project = project(), file = file(project);
        var published = new AtomicReference<byte[]>();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(
                        call -> {
                            RequestBody body = call.getArgument(1);
                            try (var stream = body.contentStreamProvider().newStream()) {
                                published.set(stream.readAllBytes());
                            }
                            return PutObjectResponse.builder().build();
                        });
        UUID request =
                UUID.fromString(
                        read(mockMvc.perform(
                                                as(
                                                        body(
                                                                post(
                                                                        "/projects/"
                                                                                + project
                                                                                + "/graph-refresh"),
                                                                "{\"files\":[\"" + file + "\"]}")))
                                        .andExpect(status().isAccepted())
                                        .andReturn())
                                .get("request_id")
                                .asString());
        var input = json.readTree(published.get());
        assertThat(input.get("schema_version").asString()).isEqualTo("refresh-input-v1");
        assertThat(input.get("documents").get(0).get("body_text").asString()).isEmpty();
        String fingerprint =
                HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(published.get()));
        var event =
                json.readTree(
                        jdbcClient
                                .sql(
                                        "select payload::text from outbox_events where"
                                            + " aggregate_id=:id and"
                                            + " event_type='GraphRefreshRequested'")
                                .param("id", request)
                                .query(String.class)
                                .single());
        assertThat(event.get("input_fingerprint").asString()).isEqualTo(fingerprint);
        assertThat(event.get("user_id").asString()).isEqualTo(owner.toString());
        assertThat(event.get("required_events").size()).isEqualTo(1);
        assertThat(
                        jdbcClient
                                .sql(
                                        "select count(*) from outbox_events where aggregate_id=:id"
                                            + " and event_type='FileChanged'")
                                .param("id", file)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        mockMvc.perform(
                        as(
                                body(
                                        post("/projects/" + project + "/graph-refresh"),
                                        "{\"files\":[\"" + file + "\"]}")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(get("/projects/" + project + "/graph-refresh/" + request), stranger))
                .andExpect(status().isNotFound());
        String result =
                """
{"schema_version":"refresh-result-v3","request_id":"%s","project_id":"%s","outcome":"NO_CHANGE",
 "document_proposals":[],"relation_proposals":[],"new_document_proposals":[],
 "execution":{"input_fingerprint":"%s"}}
"""
                        .formatted(request, project, fingerprint);
        when(s3.getObject(any(GetObjectRequest.class)))
                .thenAnswer(
                        call ->
                                new ResponseInputStream<>(
                                        GetObjectResponse.builder().build(),
                                        new ByteArrayInputStream(
                                                result.getBytes(StandardCharsets.UTF_8))));
        var completion =
                new GraphRefreshCompletedEvent(
                        request,
                        project,
                        GraphRefreshCompletedEvent.Outcome.SUCCEEDED,
                        new GraphRefreshCompletedEvent.Result(
                                "refresh-private-test",
                                "refresh/" + project + "/" + request + "/result.json"),
                        null,
                        "refresh-prompt-v13");
        transaction.executeWithoutResult(s -> completed.handle(completion));
        transaction.executeWithoutResult(s -> completed.handle(completion));
        verify(s3, times(1)).getObject(any(GetObjectRequest.class));
        mockMvc.perform(as(get("/projects/" + project + "/graph-refresh/" + request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.result.outcome").value("NO_CHANGE"));
        assertThat(
                        jdbcClient
                                .sql("select revision_no from document where id=:id")
                                .param("id", file)
                                .query(Long.class)
                                .single())
                .isZero();
        String key = "refresh/" + project + "/" + request + "/input.json";
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(S3Object.builder().key(key).build()).build());
        mockMvc.perform(as(post("/projects/" + project + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/projects/" + project))).andExpect(status().isNoContent());
        verify(s3).deleteObject(argThat((DeleteObjectRequest value) -> value.key().equals(key)));
    }

    @Test
    void timeoutAndLateCompletionCannotReopenARequest() throws Exception {
        UUID project = project(), file = file(project);
        UUID request = UUID.fromString(read(mockMvc.perform(as(body(
                post("/projects/" + project + "/graph-refresh"),
                "{\"files\":[\"" + file + "\"]}"))).andExpect(status().isAccepted()).andReturn())
                .get("request_id").asString());
        jdbcClient.sql("update refresh_runs set started_at=now()-interval '2 hours' where id=:id")
                .param("id", request).update();
        timeouts.expire();
        var event = new GraphRefreshCompletedEvent(request, project,
                GraphRefreshCompletedEvent.Outcome.SUCCEEDED,
                new GraphRefreshCompletedEvent.Result("refresh-private-test", "refresh/"+project+"/"+request+"/result.json"),
                null, "refresh-prompt-v13");
        transaction.executeWithoutResult(s -> completed.handle(event));
        mockMvc.perform(as(get("/projects/"+project+"/graph-refresh/"+request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        verify(s3, never()).getObject(any(GetObjectRequest.class));
        assertThat(jdbcClient.sql("select count(*) from refresh_results where refresh_run_id=:id")
                .param("id", request).query(Long.class).single()).isZero();
    }

    @Test
    void failedUploadCannotCommitRequestOrOutbox() throws Exception {
        UUID project = project(), file = file(project);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new IllegalStateException("test"));
        mockMvc.perform(
                        as(
                                body(
                                        post("/projects/" + project + "/graph-refresh"),
                                        "{\"files\":[\"" + file + "\"]}")))
                .andExpect(status().isInternalServerError());
        assertThat(
                        jdbcClient
                                .sql("select count(*) from refresh_runs where project_id=:id")
                                .param("id", project)
                                .query(Long.class)
                                .single())
                .isZero();
        assertThat(
                        jdbcClient
                                .sql(
                                        "select count(*) from outbox_events where"
                                            + " event_type='GraphRefreshRequested' and"
                                            + " event_key=:id")
                                .param("id", project.toString())
                                .query(Long.class)
                                .single())
                .isZero();
    }

    @Test
    void renameTrashRestoreAndPermanentDeletePublishSnapshots() throws Exception {
        UUID project = project(), file = file(project);
        mockMvc.perform(as(body(patch("/files/" + file), "{\"title\":\"새 이름\"}")))
                .andExpect(status().isOk());
        mockMvc.perform(as(post("/files/" + file + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(post("/files/" + file + "/restore"))).andExpect(status().isOk());
        mockMvc.perform(as(post("/files/" + file + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/files/" + file))).andExpect(status().isNoContent());
        var kinds =
                jdbcClient
                        .sql(
                                "select payload->>'change_type' from outbox_events where"
                                    + " aggregate_id=:id order by seq")
                        .param("id", file)
                        .query(String.class)
                        .list();
        assertThat(kinds)
                .containsExactly("CREATED", "UPDATED", "TRASHED", "RESTORED", "TRASHED", "DELETED");
        assertThat(
                        jdbcClient
                                .sql("select count(*) from projection_heads where file_id=:id")
                                .param("id", file)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }
}
