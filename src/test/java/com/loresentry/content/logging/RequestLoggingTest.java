package com.loresentry.content.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import com.loresentry.content.support.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * gateway가 부른 요청 하나를 실제 필터 순서(observation → RequestLogFilter)로 보내 확인한다.
 * <ul>
 *   <li>gateway가 보낸 traceparent를 이어, 이 서비스의 모든 줄이 gateway와 같은 trace ID를 갖는다</li>
 *   <li>에러 본문 trace_id가 그 trace ID다</li>
 *   <li>gateway가 확인한 사용자(X-User-Id)가 user.id로, 받은 경로(url.path)와 경로 템플릿(http.route)이 모든 줄에 붙는다</li>
 *   <li>실패 이유(우리 오류 코드)는 따로 찍히지 않고 결과 줄(request completed)의 error.type에 붙는다</li>
 *   <li>헬스체크 흐름의 모든 줄에 tags: health가 붙는다</li>
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingTest extends ApiTestSupport {
    private static final String GATEWAY_TRACE = "0af7651916cd43dd8448eb211c80319c";

    private List<JsonNode> lines(CapturedOutput output, String traceId) {
        return output.getOut().lines().filter(line -> line.startsWith("{")).map(json::readTree)
                .filter(line -> traceId.equals(line.at("/trace/id").asString())).toList();
    }

    @Test
    void continuesTheGatewayTraceAndPutsTheUserAndPathOnEveryLine(CapturedOutput output) throws Exception {
        UUID missing = UUID.randomUUID();
        var response = mockMvc.perform(as(get("/projects/" + missing).queryParam("q", "secret-query")
                        .header("traceparent", "00-" + GATEWAY_TRACE + "-b7ad6b7169203331-01")))
                .andExpect(status().isNotFound()).andReturn().getResponse();

        assertThat(json.readTree(response.getContentAsString()).get("trace_id").asString()).isEqualTo(GATEWAY_TRACE);
        var mine = lines(output, GATEWAY_TRACE);
        assertThat(mine).extracting(line -> line.get("message").asString()).contains("request received", "request completed");
        assertThat(mine).allSatisfy(line -> {
            assertThat(line.at("/user/id").asString()).isEqualTo(owner.toString());
            assertThat(line.at("/url/path").asString()).isEqualTo("/projects/" + missing);
            assertThat(line.at("/http/route").asString()).isEqualTo("/projects/{projectId}");
        });
        JsonNode completed = mine.stream().filter(line -> "request completed".equals(line.get("message").asString()))
                .findFirst().orElseThrow();
        assertThat(completed.at("/log/level").asString()).isEqualTo("WARN");
        assertThat(completed.at("/http/response/status_code").asLong()).isEqualTo(404L);
        assertThat(completed.at("/error/type").asString()).isEqualTo("PROJECT_NOT_FOUND");
        assertThat(completed.at("/service/name").asString()).isEqualTo("content-api");
        assertThat(completed.at("/log/layer").asString()).isEqualTo("filter");
        // 실패는 결과 줄 하나뿐이다
        assertThat(mine).filteredOn(line -> "ERROR".equals(line.at("/log/level").asString())).isEmpty();
        // 쿼리스트링은 어떤 줄에도 남지 않는다
        assertThat(output.getOut()).doesNotContain("secret-query");
    }

    @Test
    void healthChecksAreTracedAndEveryLineIsTaggedHealth(CapturedOutput output) throws Exception {
        String trace = "1af7651916cd43dd8448eb211c80319c";
        mockMvc.perform(get("/health").header("traceparent", "00-" + trace + "-b7ad6b7169203331-01"))
                .andExpect(status().isOk());
        var health = lines(output, trace);
        assertThat(health).extracting(line -> line.get("message").asString()).contains("request received", "request completed");
        assertThat(health).allSatisfy(line -> {
            assertThat(line.get("tags").toString()).contains("health");
            assertThat(line.at("/http/route").asString()).isEqualTo("/health");
            assertThat(line.has("user")).isFalse();
        });
    }
}
