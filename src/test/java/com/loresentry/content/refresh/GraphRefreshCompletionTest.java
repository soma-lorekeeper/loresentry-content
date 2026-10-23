package com.loresentry.content.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.refresh.GraphRefreshCompletedEvent.ErrorDetail;
import com.loresentry.content.refresh.GraphRefreshCompletedEvent.Outcome;
import com.loresentry.content.refresh.GraphRefreshCompletedEvent.Result;
import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import tools.jackson.databind.JsonNode;

/**
 * graph-rag 의 완료 이벤트를 받아 결과 파일을 읽고 반영한다. 요청은 API 로 보내고, S3 는 흉내 낸다(올린 입력은 버리고,
 * 결과 파일은 테스트가 정한 바이트와 sha256 메타데이터로 돌려준다). listener 가 하는 일(가져오기 → 처리 기록 트랜잭션
 * 안에서 반영)을 그대로 따라 부른다.
 */
@TestPropertySource(properties = {
        "graph-refresh.request-bucket=" + GraphRefreshCompletionTest.BUCKET,
        "graph-refresh.completed-bucket=" + GraphRefreshCompletionTest.BUCKET,
        "graph-refresh.result-max-bytes=" + GraphRefreshCompletionTest.MAX_BYTES })
class GraphRefreshCompletionTest extends ApiTestSupport {

    static final String BUCKET = "refresh-completion-bucket";

    static final long MAX_BYTES = 200_000;

    @MockitoBean(name = "refreshS3Client")
    private S3Client s3;

    @Autowired
    private GraphRefreshService service;

    @Autowired
    private TransactionTemplate transaction;

    private UUID project;

    private UUID chapter;

    private UUID hero;

    private UUID neighbor;

    @BeforeEach
    void seed() throws Exception {
        willAnswer(invocation -> PutObjectResponse.builder().build())
                .given(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        project = idOf(mockMvc.perform(as(body(post("/projects"), "{\"name\":\"완료 반영\"}"))).andReturn());
        chapter = createDocument("MANUSCRIPT", "1화");
        hero = createDocument("CHARACTER", "유나");
        neighbor = createDocument("CHARACTER", "도윤");
        save(hero, "유나", "파란 망토를 입는다.", "[{\"relation_key\":\"related_manuscript\",\"target_document_id\":\""
                + chapter + "\",\"description\":\"첫 등장\"}]");
    }

    /**
     * 첫 최신화를 끝낸 뒤 원고만 고쳐 다시 요청한다. 그러면 원고만 S3 에 실리고(요청 때 작업본·잠금), 유나·도윤은
     * graph-rag 가 목록 API 로 더 읽는 이웃이 된다.
     */
    private UUID requestWithOnlyTheChapterChanged() throws Exception {
        UUID first = requestRefresh();
        // 첫 run 이 확정된 것처럼 끝낸다: 상태, 작업본·기준 버전 정리, 잠금 해제.
        jdbcClient.sql("update refresh_runs set status = 'APPLIED' where id = :id").param("id", first).update();
        jdbcClient.sql("delete from refresh_document_drafts where refresh_run_id = :id").param("id", first).update();
        jdbcClient.sql("delete from document_versions where kind = 'REFRESH_BASE' "
                        + "and document_id in (select id from document where project_id = :project)")
                .param("project", project).update();
        jdbcClient.sql("update document set refresh_lock_run_id = null where refresh_lock_run_id = :id")
                .param("id", first).update();
        // 저장은 관계 목록을 통째로 맞추므로 유나와의 관계를 그대로 보낸다.
        save(chapter, "1화", "유나는 은색 검을 들었다.", "[{\"relation_key\":\"related_character\","
                + "\"target_document_id\":\"" + hero + "\",\"description\":\"첫 등장\"}]");
        return requestRefresh();
    }

    @Test
    void proposalsOpenDraftsForSentNeighborAndNewDocumentsAndLockThem() throws Exception {
        UUID run = requestWithOnlyTheChapterChanged();
        long heroRevision = revisionOf(hero);
        long neighborRevision = revisionOf(neighbor);
        long chapterRevision = revisionOf(chapter);
        String result = result(run, "PROPOSED", """
                "document_proposals":[{"target_document_id":"%s","base_revision_no":%d,"field":"body_text",
                  "value":"파란 망토를 입는다.\\n은색 검을 든다.","fact_ids":["F1"],"evidence_refs":["E0001"]}],
                "new_document_proposals":[{"candidate_id":"new:ITEM:은색 검","folder_code":"ITEM","name":"은색 검",
                  "body_text":"유나의 검이다.","fact_ids":["F1"],"evidence_refs":["E0001"]}],
                "relation_proposals":[
                  {"document_id":"%s","target_document_id":"%s","base_revision_no":%d,"target_base_revision_no":%d,
                   "relation_key":"related_character","reverse_relation_key":"related_manuscript",
                   "description":"도윤이 지켜본다.","operation":"ADD","fact_ids":["F1"],"evidence_refs":["E0001"]},
                  {"document_id":"%s","target_document_id":"new:ITEM:은색 검","base_revision_no":%d,
                   "target_base_revision_no":null,"relation_key":"related_item","reverse_relation_key":"related_character",
                   "description":"유나의 검","operation":"ADD","fact_ids":["F1"],"evidence_refs":["E0001"]}],
                "facts":[{"fact_id":"F1","statement":"유나는 은색 검을 들었다."}],
                "evidence":[{"evidence_id":"E0001","document_id":"%s","revision_no":%d,"start":0,"end":13,
                  "quote":"유나는 은색 검을 들었다."}]
                """.formatted(hero, heroRevision, chapter, neighbor, chapterRevision, neighborRevision, hero,
                heroRevision, chapter, chapterRevision));

        complete(run, Outcome.SUCCEEDED, result);

        assertThat(runStatus(run)).containsEntry("status", "READY").containsEntry("prompt_version", "v13")
                .containsEntry("ready", true);
        assertThat(jdbcClient.sql("select sha256 from refresh_results where refresh_run_id = :run")
                .param("run", run).query(String.class).single()).isEqualTo(sha256Hex(result));

        Map<UUID, JsonNode> rights = rights(run);
        // 원고: 요청 때 만든 작업본이 열리고, 도윤과의 관계가 right 에 더해진다. 본문은 그대로다.
        assertThat(rights.get(chapter).get("relations")).isEqualTo(sortedCharacterRelations(
                Map.of(neighbor, "도윤이 지켜본다.", hero, "첫 등장")));
        // 유나: 이웃이라 새 작업본. 첫 줄은 원래 블록 그대로, 둘째 줄은 새 문단. 새 문서 제안과의 관계가 더해진다.
        JsonNode heroRight = rights.get(hero);
        assertThat(heroRight.at("/body/doc/content/0/content/0/text").stringValue()).isEqualTo("파란 망토를 입는다.");
        assertThat(heroRight.at("/body/doc/content/1/content/0/text").stringValue()).isEqualTo("은색 검을 든다.");
        assertThat(heroRight.get("relations").findValuesAsString("target_candidate_id")).contains("new:ITEM:은색 검");
        // 도윤: 관계만 바뀌는 이웃. 원고와의 관계가 right 에 있다.
        assertThat(rights.get(neighbor).get("relations").get(0).get("target_document_id").stringValue())
                .isEqualTo(chapter.toString());

        Map<String, Object> newDraft = jdbcClient.sql("""
                        select kind, folder_code, left_snapshot is null as no_left, right_snapshot::text as right_json,
                               status, matched
                        from refresh_document_drafts where refresh_run_id = :run and kind = 'NEW'
                        """)
                .param("run", run)
                .query((rows, index) -> Map.<String, Object>of("folder", rows.getString("folder_code"),
                        "no_left", rows.getBoolean("no_left"), "right", rows.getString("right_json"),
                        "status", rows.getString("status"), "matched", rows.getBoolean("matched")))
                .single();
        assertThat(newDraft).containsEntry("folder", "ITEM").containsEntry("no_left", true)
                .containsEntry("status", "OPEN").containsEntry("matched", false);
        JsonNode newRight = json.readTree((String) newDraft.get("right"));
        assertThat(newRight.get("title").stringValue()).isEqualTo("은색 검");
        assertThat(newRight.get("relations").get(0).get("target_document_id").stringValue()).isEqualTo(hero.toString());
        assertThat(newRight.get("relations").get(0).get("relation_key").stringValue()).isEqualTo("related_character");

        // 이웃의 작업본에는 기준 버전이 없다. 모든 대상은 확정까지 잠긴다.
        assertThat(jdbcClient.sql("select count(*) from refresh_document_drafts where refresh_run_id = :run "
                        + "and kind = 'EDIT' and base_version_id is null and status = 'OPEN'")
                .param("run", run).query(Long.class).single()).isEqualTo(2);
        for (UUID locked : List.of(chapter, hero, neighbor)) {
            mockMvc.perform(as(get("/files/" + locked + "/content")))
                    .andExpect(jsonPath("$.refresh_locked").value(true));
        }
    }

    /** 캐릭터 문서로 가는 관계들의 정규형: 관계 키가 같으므로 대상 id(문자열) 순이다. */
    private JsonNode sortedCharacterRelations(Map<UUID, String> descriptions) {
        List<UUID> targets = new ArrayList<>(descriptions.keySet());
        targets.sort((a, b) -> a.toString().compareTo(b.toString()));
        return json.readTree("[" + String.join(",", targets.stream().map(target ->
                "{\"relation_key\":\"related_character\",\"target_document_id\":\"" + target
                        + "\",\"target_candidate_id\":null,\"description\":\"" + descriptions.get(target) + "\"}")
                .toList()) + "]");
    }

    @Test
    void noChangeEndsTheRunAndReleasesEverything() throws Exception {
        UUID run = requestRefresh();

        complete(run, Outcome.SUCCEEDED, result(run, "NO_CHANGE", ""));

        assertThat(runStatus(run)).containsEntry("status", "NO_CHANGE").containsEntry("completed", true);
        assertReleased(run);
    }

    @Test
    void aFailedEventEndsTheRunWithItsCodeAndKeepsAReadableResult() throws Exception {
        UUID run = requestRefresh();
        String result = result(run, "FAILED", "\"error\":{\"code\":\"MODEL_REFUSED\",\"message\":\"refused\","
                + "\"retryable\":false}");
        stubResult(run, result, sha256Hex(result));

        GraphRefreshCompletedEvent event = new GraphRefreshCompletedEvent(run, project, Outcome.FAILED,
                new Result(BUCKET, key(run)), new ErrorDetail("MODEL_REFUSED", "refused"), "v13");
        apply(event);

        assertThat(runStatus(run)).containsEntry("status", "FAILED").containsEntry("error_code", "MODEL_REFUSED");
        assertThat(jdbcClient.sql("select count(*) from refresh_results where refresh_run_id = :run")
                .param("run", run).query(Long.class).single()).isEqualTo(1);
        assertReleased(run);
    }

    @Test
    void anUnusableResultFailsTheRun() throws Exception {
        UUID mismatched = requestRefresh();
        String result = result(mismatched, "NO_CHANGE", "");
        stubResult(mismatched, result, "0".repeat(64));
        apply(succeeded(mismatched));
        assertThat(runStatus(mismatched)).containsEntry("status", "FAILED").containsEntry("error_code", "RESULT_INVALID");
        assertReleased(mismatched);

        UUID expired = requestRefreshAfterAnotherDocument();
        willThrow(NoSuchKeyException.builder().message("gone").build())
                .given(s3).getObject(any(GetObjectRequest.class));
        apply(succeeded(expired));
        assertThat(runStatus(expired)).containsEntry("error_code", "RESULT_EXPIRED");

        UUID oversized = requestRefreshAfterAnotherDocument();
        willAnswer(invocation -> new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(MAX_BYTES + 1).metadata(Map.of("sha256", "x")).build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[0]))))
                .given(s3).getObject(any(GetObjectRequest.class));
        apply(succeeded(oversized));
        assertThat(runStatus(oversized)).containsEntry("error_code", "RESULT_TOO_LARGE");

        UUID elsewhere = requestRefreshAfterAnotherDocument();
        apply(new GraphRefreshCompletedEvent(elsewhere, project, Outcome.SUCCEEDED,
                new Result("another-bucket", key(elsewhere)), null, "v13"));
        assertThat(runStatus(elsewhere)).containsEntry("error_code", "RESULT_LOCATION_INVALID");

        UUID foreign = requestRefreshAfterAnotherDocument();
        String otherRequest = result(UUID.randomUUID(), "NO_CHANGE", "");
        stubResult(foreign, otherRequest, sha256Hex(otherRequest));
        apply(succeeded(foreign));
        assertThat(runStatus(foreign)).containsEntry("error_code", "RESULT_INVALID");
    }

    /** 이미 끝난 run(시간 제한 등)이나 모르는 run 의 완료는 S3 도 읽지 않고 아무것도 바꾸지 않는다. */
    @Test
    void lateAndUnknownCompletionsDoNothing() throws Exception {
        UUID run = requestRefresh();
        jdbcClient.sql("update refresh_runs set status = 'FAILED', error_code = 'TIMED_OUT' where id = :id")
                .param("id", run).update();

        apply(succeeded(run));
        apply(new GraphRefreshCompletedEvent(UUID.randomUUID(), project, Outcome.SUCCEEDED,
                new Result(BUCKET, "refresh/x/y/result.json"), null, "v13"));

        assertThat(runStatus(run)).containsEntry("status", "FAILED").containsEntry("error_code", "TIMED_OUT");
        verify(s3, never()).getObject(any(GetObjectRequest.class));
    }

    /** 사용자가 잠근 문서와 휴지통 문서에 온 제안은 버린다. 남는 제안이 없으면 "변경 없음"이다. */
    @Test
    void proposalsForUserLockedOrTrashedDocumentsAreDropped() throws Exception {
        mockMvc.perform(as(body(put("/files/" + hero + "/lock"), "{\"locked\":true}")))
                .andExpect(status().isOk());
        UUID run = requestWithOnlyTheChapterChanged();
        mockMvc.perform(as(post("/files/" + neighbor + "/trash"))).andExpect(status().isNoContent());

        complete(run, Outcome.SUCCEEDED, result(run, "PROPOSED", """
                "document_proposals":[
                  {"target_document_id":"%s","base_revision_no":1,"field":"body_text","value":"잠긴 문서"},
                  {"target_document_id":"%s","base_revision_no":0,"field":"body_text","value":"버린 문서"}]
                """.formatted(hero, neighbor)));

        assertThat(runStatus(run)).containsEntry("status", "NO_CHANGE");
        assertReleased(run);
    }

    // --- 도우미 -------------------------------------------------------------------------------------------------

    /** listener 처럼 결과를 먼저 가져오고, 트랜잭션 안에서 반영한다. */
    private void apply(GraphRefreshCompletedEvent event) {
        ResultFetch fetched = service.fetchResult(event);
        transaction.executeWithoutResult(status -> service.complete(event, fetched));
    }

    private void complete(UUID run, Outcome outcome, String result) {
        stubResult(run, result, sha256Hex(result));
        apply(new GraphRefreshCompletedEvent(run, project, outcome, new Result(BUCKET, key(run)), null, "v13"));
    }

    private GraphRefreshCompletedEvent succeeded(UUID run) {
        return new GraphRefreshCompletedEvent(run, project, Outcome.SUCCEEDED, new Result(BUCKET, key(run)), null,
                "v13");
    }

    private String key(UUID run) {
        return "refresh/" + project + "/" + run + "/result.json";
    }

    /** 결과 파일. graph-rag 가 쓰는 모양에서 이 테스트가 보는 칸만 채운다. */
    private String result(UUID run, String outcome, String fields) {
        return """
                {"schema_version":"refresh-result-v3","request_id":"%s","project_id":"%s","outcome":"%s",
                 "document_proposals":[],"new_document_proposals":[],"relation_proposals":[],"facts":[],
                 "evidence":[],"sources":[],"error":null,"execution":{"model":"gpt-6-luna"}%s}
                """.formatted(run, project, outcome, fields.isBlank() ? "" : "," + fields);
    }

    private void stubResult(UUID run, String result, String sha256) {
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        willAnswer(invocation -> {
            GetObjectRequest request = invocation.getArgument(0);
            assertThat(request.bucket()).isEqualTo(BUCKET);
            assertThat(request.key()).isEqualTo(key(run));
            return new ResponseInputStream<>(
                    GetObjectResponse.builder().contentLength((long) bytes.length)
                            .metadata(Map.of("sha256", sha256)).build(),
                    AbortableInputStream.create(new ByteArrayInputStream(bytes)));
        }).given(s3).getObject(any(GetObjectRequest.class));
    }

    /** 작업본·기준 버전이 없고 문서 잠금이 풀렸다. */
    private void assertReleased(UUID run) throws Exception {
        assertThat(jdbcClient.sql("select count(*) from refresh_document_drafts where refresh_run_id = :run")
                .param("run", run).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from document_versions v join document d on d.id = v.document_id "
                        + "where d.project_id = :project and v.kind = 'REFRESH_BASE'")
                .param("project", project).query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from document where refresh_lock_run_id = :run")
                .param("run", run).query(Long.class).single()).isZero();
    }

    private Map<String, Object> runStatus(UUID run) {
        return jdbcClient.sql("""
                        select status, coalesce(error_code, '') as error_code, coalesce(prompt_version, '') as prompt_version,
                               ready_at is not null as ready, completed_at is not null as completed
                        from refresh_runs where id = :id
                        """)
                .param("id", run)
                .query((rows, index) -> Map.<String, Object>of("status", rows.getString("status"),
                        "error_code", rows.getString("error_code"), "prompt_version", rows.getString("prompt_version"),
                        "ready", rows.getBoolean("ready"), "completed", rows.getBoolean("completed")))
                .single();
    }

    /** 이 run 의 열린 기존 문서 작업본들의 right, 대상 문서별. */
    private Map<UUID, JsonNode> rights(UUID run) {
        Map<UUID, JsonNode> rights = new HashMap<>();
        jdbcClient.sql("select target_document_id, right_snapshot::text from refresh_document_drafts "
                        + "where refresh_run_id = :run and kind = 'EDIT' and status = 'OPEN'")
                .param("run", run)
                .query(rows -> {
                    rights.put(rows.getObject(1, UUID.class), json.readTree(rows.getString(2)));
                });
        return rights;
    }

    /** 앞 run 을 끝내고 문서를 하나 더 만들어 새 요청을 보낸다. 한 테스트에서 여러 run 을 쓸 때. */
    private UUID requestRefreshAfterAnotherDocument() throws Exception {
        createDocument("EVENT", "사건 " + UUID.randomUUID());
        return requestRefresh();
    }

    private UUID requestRefresh() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(read(result).get("request_id").stringValue());
    }

    private UUID createDocument(String folderCode, String title) throws Exception {
        return idOf(mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode + "\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private void save(UUID file, String title, String text, String relations) throws Exception {
        mockMvc.perform(as(body(put("/files/" + file + "/content"), """
                        {"title":"%s","body":{"schema_version":1,"doc":{"type":"doc","content":[
                          {"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}},
                         "properties":[],"relations":%s}
                        """.formatted(title, text, relations)))
                        .header(HttpHeaders.IF_MATCH, "\"" + revisionOf(file) + "\""))
                .andExpect(status().isOk());
    }

    private long revisionOf(UUID file) throws Exception {
        return read(mockMvc.perform(as(get("/files/" + file + "/content"))).andReturn()).get("revision_no").asLong();
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
