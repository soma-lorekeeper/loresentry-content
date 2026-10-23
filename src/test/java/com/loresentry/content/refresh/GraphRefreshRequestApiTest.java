package com.loresentry.content.refresh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import tools.jackson.databind.JsonNode;

/**
 * 그래프 최신화 요청을 API 로 보내고, 무엇이 S3 에 올라가고 무엇이 기록·발행되는지 본다. S3 는 흉내 내고 올린 요청과
 * 바이트를 붙잡는다. outbox_events 와 refresh_runs 는 테스트 사이에 지워지지 않으므로 이 테스트의 프로젝트로 거른다.
 */
@TestPropertySource(properties = "graph-refresh.request-bucket=" + GraphRefreshRequestApiTest.BUCKET)
class GraphRefreshRequestApiTest extends ApiTestSupport {

    static final String BUCKET = "refresh-test-bucket";

    @MockitoBean
    private S3Client s3;

    private UUID project;

    /** S3 에 올라간 것. 요청 순서대로 쌓인다. */
    private final List<Upload> uploads = new ArrayList<>();

    private record Upload(PutObjectRequest request, byte[] body) {
    }

    @BeforeEach
    void createProjectAndCaptureUploads() throws Exception {
        project = idOf(mockMvc.perform(as(body(post("/projects"), "{\"name\":\"최신화 확인\"}")))
                .andExpect(status().isCreated())
                .andReturn());
        willAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            uploads.add(new Upload(invocation.getArgument(0), body.contentStreamProvider().newStream().readAllBytes()));
            return PutObjectResponse.builder().build();
        }).given(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /** 처음 요청하면 모든 폴더의 활성 문서가 실린다. 휴지통 문서와 남의 프로젝트 문서는 빠진다. */
    @Test
    void firstRequestSendsEveryActiveDocumentOfEveryFolder() throws Exception {
        UUID chapter = createDocument("MANUSCRIPT", "1화");
        UUID character = createDocument("CHARACTER", "서윤");
        save(character, "서윤", "기록관의 막내", "[{\"key\":\"alias\",\"value\":\"윤\"}]",
                "[{\"relation_key\":\"related_manuscript\",\"target_document_id\":\"" + chapter
                        + "\",\"description\":\"첫 등장\"}]");
        UUID trashed = createDocument("ITEM", "버린 아이템");
        mockMvc.perform(as(post("/files/" + trashed + "/trash"))).andExpect(status().isNoContent());

        MvcResult result = mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("GENERATING"))
                .andReturn();
        UUID request = UUID.fromString(read(result).get("request_id").stringValue());
        long characterRevision = revisionOf(character);

        // 응답과 기준선 기록: 실은 문서와 그 revision 이다.
        assertThat(read(result).get("files")).isEqualTo(json.readTree(filesJson(sorted(Map.of(
                chapter, 0L, character, characterRevision)))));
        assertThat(jdbcClient.sql("select file_id, revision_no from refresh_run_files where refresh_run_id = :run "
                        + "order by file_id")
                .param("run", request)
                .query((rows, index) -> Map.entry(rows.getObject("file_id", UUID.class), rows.getLong("revision_no")))
                .list())
                .containsExactlyElementsOf(sorted(Map.of(chapter, 0L, character, characterRevision)).entrySet());
        assertThat(jdbcClient.sql("select status, requested_by::text, prompt_version from refresh_runs where id = :id")
                .param("id", request)
                .query((rows, index) -> List.of(rows.getString(1), rows.getString(2),
                        String.valueOf(rows.getString(3))))
                .single())
                .containsExactly("GENERATING", owner.toString(), "null");

        // S3: 위치와 덮어쓰기 금지 조건, 중복 없이 정보만 담은 입력.
        assertThat(uploads).hasSize(1);
        Upload upload = uploads.getFirst();
        String key = "refresh/" + project + "/" + request + "/input.json";
        assertThat(upload.request().bucket()).isEqualTo(BUCKET);
        assertThat(upload.request().key()).isEqualTo(key);
        assertThat(upload.request().ifNoneMatch()).isEqualTo("*");
        assertThat(upload.request().contentType()).isEqualTo("application/json");
        String chapterJson = """
                {"id":"%s","revision_no":0,"folder_code":"MANUSCRIPT","title":"1화","body_text":"",
                 "properties":[],
                 "relations":[{"target_document_id":"%s","relation_key":"related_character","description":"첫 등장"}]}
                """.formatted(chapter, character);
        String characterJson = """
                {"id":"%s","revision_no":%d,"folder_code":"CHARACTER","title":"서윤","body_text":"기록관의 막내",
                 "properties":[{"key":"alias","value":"윤"}],
                 "relations":[{"target_document_id":"%s","relation_key":"related_manuscript","description":"첫 등장"}]}
                """.formatted(character, characterRevision, chapter);
        String documents = chapter.toString().compareTo(character.toString()) < 0
                ? chapterJson + "," + characterJson : characterJson + "," + chapterJson;
        assertThat(json.readTree(upload.body())).isEqualTo(json.readTree(
                "{\"schema_version\":\"refresh-input-v2\",\"documents\":[" + documents + "]}"));

        // 이벤트: S3 위치·바이트의 sha256·실은 문서·프로젝트 문서마다 최신 FileChanged. 모델 설정은 싣지 않는다.
        List<JsonNode> events = requestedEvents();
        assertThat(events).hasSize(1);
        assertThat(jdbcClient.sql("select aggregate_id::text || ' ' || event_key from outbox_events "
                        + "where event_type = 'GraphRefreshRequested' and event_key = :project")
                .param("project", project.toString())
                .query(String.class)
                .single())
                .isEqualTo(request + " " + project);
        JsonNode payload = events.getFirst();
        assertThat(payload.has("model_settings")).isFalse();
        assertThat(payload).isEqualTo(json.readTree("""
                {"project_id":"%s","request_id":"%s","user_id":"%s","input_fingerprint":"%s",
                 "input":{"bucket":"%s","key":"%s"},
                 "files":%s,
                 "required_events":%s}
                """.formatted(project, request, owner, sha256Hex(upload.body()), BUCKET, key,
                filesJson(sorted(Map.of(chapter, 0L, character, characterRevision))),
                json.writeValueAsString(latestFileChangedEvents()))));
    }

    /** 지난 최신화 이후 저장한 문서와 새로 만든 문서만 실린다. */
    @Test
    void nextRequestSendsOnlyChangedAndNewDocuments() throws Exception {
        UUID saved = createDocument("MANUSCRIPT", "1화");
        createDocument("CHARACTER", "그대로인 인물");
        UUID first = requestRefresh();
        finish(first, "READY");

        save(saved, "1화", "고친 본문", "[]", "[]");
        UUID created = createDocument("LOCATION", "새 장소");

        UUID second = requestRefresh();
        assertThat(sentFiles(second)).isEqualTo(sorted(Map.of(saved, revisionOf(saved), created, 0L)));
    }

    /** 실패한 최신화가 실었던 문서는 기준선이 되지 않는다. 바뀌지 않았어도 다음 요청에 다시 실린다. */
    @Test
    void documentsOfAFailedRunAreSentAgain() throws Exception {
        UUID chapter = createDocument("MANUSCRIPT", "1화");
        UUID first = requestRefresh();
        finish(first, "FAILED");

        UUID second = requestRefresh();
        assertThat(sentFiles(second)).isEqualTo(Map.of(chapter, 0L));
    }

    /** 진행 중인 최신화가 있으면 거절한다. S3 에도 올리지 않는다. */
    @Test
    void aRunningRefreshRejectsAnotherRequest() throws Exception {
        createDocument("MANUSCRIPT", "1화");
        requestRefresh();
        createDocument("MANUSCRIPT", "2화");

        mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_IN_PROGRESS"));

        assertThat(uploads).hasSize(1);
        assertThat(requestedEvents()).hasSize(1);
    }

    /** 바뀐 문서가 없으면 아무것도 기록하거나 보내지 않는다. */
    @Test
    void nothingChangedRecordsAndSendsNothing() throws Exception {
        mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isOk())
                .andExpect(content -> assertThat(read(content)).isEqualTo(json.readTree(
                        "{\"request_id\":null,\"status\":\"NO_CHANGES\",\"files\":[]}")));

        createDocument("MANUSCRIPT", "1화");
        finish(requestRefresh(), "READY");
        mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_CHANGES"));

        assertThat(runCount()).isEqualTo(1);
        assertThat(uploads).hasSize(1);
        assertThat(requestedEvents()).hasSize(1);
    }

    /** S3 에 올리지 못하면 run 도 이벤트도 남지 않는다. */
    @Test
    void storageFailureLeavesNoTrace() throws Exception {
        createDocument("MANUSCRIPT", "1화");
        willThrow(SdkClientException.create("unreachable"))
                .given(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REFRESH_UNAVAILABLE"));

        assertThat(runCount()).isZero();
        assertThat(requestedEvents()).isEmpty();
    }

    /** 남의 프로젝트와 휴지통 프로젝트는 없는 것으로 답한다. */
    @Test
    void onlyTheOwnersActiveProjectCanBeRefreshed() throws Exception {
        createDocument("MANUSCRIPT", "1화");

        mockMvc.perform(as(post("/projects/" + project + "/graph-refresh"), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        mockMvc.perform(as(post("/projects/" + project + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        assertThat(runCount()).isZero();
        assertThat(uploads).isEmpty();
    }

    private UUID requestRefresh() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(read(result).get("request_id").stringValue());
    }

    /** 완료 처리가 아직 없으므로 graph-rag 의 결과를 받은 것처럼 상태만 바꾼다. */
    private void finish(UUID run, String status) {
        jdbcClient.sql("update refresh_runs set status = :status where id = :id")
                .param("status", status).param("id", run).update();
    }

    private Map<UUID, Long> sentFiles(UUID run) {
        Map<UUID, Long> files = new LinkedHashMap<>();
        jdbcClient.sql("select file_id, revision_no from refresh_run_files where refresh_run_id = :run order by file_id")
                .param("run", run)
                .query(rows -> {
                    files.put(rows.getObject("file_id", UUID.class), rows.getLong("revision_no"));
                });
        return files;
    }

    private long runCount() {
        return jdbcClient.sql("select count(*) from refresh_runs where project_id = :project")
                .param("project", project).query(Long.class).single();
    }

    private List<JsonNode> requestedEvents() {
        return jdbcClient.sql("select payload::text from outbox_events "
                        + "where event_type = 'GraphRefreshRequested' and event_key = :project order by seq")
                .param("project", project.toString())
                .query((rows, index) -> json.readTree(rows.getString(1)))
                .list();
    }

    /** 이 프로젝트의 FileChanged 를 발행 순서대로 훑어 문서마다 마지막 것을 남긴다. 휴지통 문서도 들어간다. */
    private List<UUID> latestFileChangedEvents() {
        Map<UUID, UUID> latest = new LinkedHashMap<>();
        jdbcClient.sql("select aggregate_id, id from outbox_events "
                        + "where event_type = 'FileChanged' and event_key = :project order by seq")
                .param("project", project.toString())
                .query(rows -> {
                    latest.put(rows.getObject("aggregate_id", UUID.class), rows.getObject("id", UUID.class));
                });
        return latest.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((a, b) -> a.toString().compareTo(b.toString())))
                .map(Map.Entry::getValue)
                .toList();
    }

    /** content 와 같은 순서(uuid 문자열 순 = PostgreSQL uuid 순)로 정렬한다. */
    private static Map<UUID, Long> sorted(Map<UUID, Long> files) {
        Map<UUID, Long> ordered = new LinkedHashMap<>();
        files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((a, b) -> a.toString().compareTo(b.toString())))
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    /** 응답과 이벤트의 {@code files} 는 같은 모양이다. */
    private static String filesJson(Map<UUID, Long> files) {
        List<String> items = new ArrayList<>();
        files.forEach((id, revision) -> items.add("{\"file_id\":\"" + id + "\",\"revision_no\":" + revision + "}"));
        return "[" + String.join(",", items) + "]";
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private UUID createDocument(String folderCode, String title) throws Exception {
        return idOf(mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode + "\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn());
    }

    /** 화면이 저장할 때처럼 문서 전체를 보낸다. 본문은 문단 하나다. */
    private void save(UUID file, String title, String text, String properties, String relations) throws Exception {
        mockMvc.perform(as(body(put("/files/" + file + "/content"), """
                        {"title":"%s","body":{"schema_version":1,"doc":{"type":"doc","content":[
                          {"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}},
                         "properties":%s,"relations":%s}
                        """.formatted(title, text, properties, relations)))
                        .header(HttpHeaders.IF_MATCH, "\"" + revisionOf(file) + "\""))
                .andExpect(status().isOk());
    }

    private long revisionOf(UUID file) throws Exception {
        return read(mockMvc.perform(as(get("/files/" + file + "/content"))).andReturn()).get("revision_no").asLong();
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(read(result).get("id").stringValue());
    }
}
