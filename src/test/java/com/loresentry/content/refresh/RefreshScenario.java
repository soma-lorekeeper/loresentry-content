package com.loresentry.content.refresh;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent.Outcome;
import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent.Result;
import com.loresentry.content.support.ApiTestSupport;
import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent;
import com.loresentry.content.refresh.completion.ResultFetch;
import com.loresentry.content.refresh.completion.GraphRefreshCompletionService;

import org.junit.jupiter.api.BeforeEach;
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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * 검토·확정 테스트의 공통 준비: 프로젝트를 만들고, 최신화를 요청하고, graph-rag 결과가 도착한 것처럼 반영해 run 을
 * READY 로 만든다. S3 는 흉내 낸다(올린 입력은 버리고, 결과 파일은 테스트가 정한 바이트와 sha256 메타데이터로 준다).
 */
@TestPropertySource(properties = {
        "graph-refresh.request-bucket=" + RefreshScenario.BUCKET,
        "graph-refresh.completed-bucket=" + RefreshScenario.BUCKET })
public abstract class RefreshScenario extends ApiTestSupport {

    protected static final String BUCKET = "refresh-scenario-bucket";

    @MockitoBean(name = "refreshS3Client")
    protected S3Client s3;

    @Autowired
    protected GraphRefreshCompletionService service;

    @Autowired
    protected TransactionTemplate transaction;

    protected UUID project;

    @BeforeEach
    protected void createProjectAndAcceptUploads() throws Exception {
        willAnswer(invocation -> PutObjectResponse.builder().build())
                .given(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        project = idOf(mockMvc.perform(as(body(post("/projects"), "{\"name\":\"검토와 확정\"}"))).andReturn());
    }

    /** graph-rag 가 성공 결과를 보낸 것처럼, listener 와 같은 순서로(가져오기 → 트랜잭션 안에서 반영) 처리한다. */
    protected void complete(UUID run, String outcome, String fields) {
        String result = """
                {"schema_version":"refresh-result-v3","request_id":"%s","project_id":"%s","outcome":"%s",
                 "document_proposals":[],"new_document_proposals":[],"relation_proposals":[],"facts":[],
                 "evidence":[],"sources":[],"error":null%s}
                """.formatted(run, project, outcome, fields.isBlank() ? "" : "," + fields);
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256Hex(bytes);
        willAnswer(invocation -> new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) bytes.length).metadata(Map.of("sha256", sha256))
                        .build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes))))
                .given(s3).getObject(any(GetObjectRequest.class));
        GraphRefreshCompletedEvent event = new GraphRefreshCompletedEvent(run, project, Outcome.SUCCEEDED,
                new Result(BUCKET, "refresh/" + project + "/" + run + "/result.json"), null, "v13");
        ResultFetch fetched = service.fetchResult(event);
        transaction.executeWithoutResult(status -> service.complete(event, fetched));
    }

    protected UUID requestRefresh() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isAccepted())
                .andReturn();
        return UUID.fromString(read(result).get("request_id").stringValue());
    }

    protected UUID createDocument(String folderCode, String title) throws Exception {
        return idOf(mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode + "\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn());
    }

    /** 화면이 저장할 때처럼 문서 전체를 보낸다. 본문은 문단 하나다. 관계는 목록 그대로 맞춰진다. */
    protected void save(UUID file, String title, String text, String relations) throws Exception {
        mockMvc.perform(as(body(put("/files/" + file + "/content"), """
                        {"title":"%s","body":{"schema_version":1,"doc":{"type":"doc","content":[
                          {"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}},
                         "properties":[],"relations":%s}
                        """.formatted(title, text, relations)))
                        .header(HttpHeaders.IF_MATCH, "\"" + revisionOf(file) + "\""))
                .andExpect(status().isOk());
    }

    protected long revisionOf(UUID file) throws Exception {
        return read(mockMvc.perform(as(get("/files/" + file + "/content"))).andReturn()).get("revision_no").asLong();
    }

    protected UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(read(result).get("id").stringValue());
    }

    protected static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
