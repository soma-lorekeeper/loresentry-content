package com.loresentry.content.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** EcsLogFormatter가 LOG_SCHEMA.md 5절의 중첩 ECS JSON을 한 줄로 만드는지 확인한다. */
class EcsLogFormatterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final LoggerContext context = new LoggerContext();
    private final EcsLogFormatter formatter = new EcsLogFormatter(new MockEnvironment()
            .withProperty("logging.structured.ecs.service.name", "content-api")
            .withProperty("logging.structured.ecs.service.version", "build-1-1"));

    private LoggingEvent event(Level level, String message, Throwable cause) {
        return new LoggingEvent("test", context.getLogger("com.loresentry.content.Sample"), level, message, cause, null);
    }

    private JsonNode format(LoggingEvent event) {
        String line = formatter.format(event);
        assertThat(line).endsWith("\n").doesNotContain("\n\t").hasLineCount(1);
        return JSON.readTree(line);
    }

    @Test
    void writesRequiredFieldsAsNestedObjects() {
        JsonNode json = format(event(Level.INFO, "request received", null));
        assertThat(json.get("@timestamp").asString()).isNotBlank();
        assertThat(json.at("/log/level").asString()).isEqualTo("INFO");
        assertThat(json.at("/log/logger").asString()).isEqualTo("com.loresentry.content.Sample");
        assertThat(json.at("/service/name").asString()).isEqualTo("content-api");
        assertThat(json.at("/service/version").asString()).isEqualTo("build-1-1");
        assertThat(json.at("/ecs/version").asString()).isEqualTo("8.11");
        assertThat(json.get("message").asString()).isEqualTo("request received");
        // 점이 든 키({"log.level":..})는 Logstash가 필드로 읽지 못한다
        assertThat(json.has("log.level")).isFalse();
    }

    @Test
    void mergesKeyValuesIntoTheSameObjectsTheFormatterWrites() {
        var event = event(Level.WARN, "upstream call completed", null);
        event.addKeyValuePair(new KeyValuePair("log.origin.function", "intercept"));
        event.addKeyValuePair(new KeyValuePair("loresentry.project_id", "p-1"));
        event.addKeyValuePair(new KeyValuePair("http.response.status_code", 503L));
        JsonNode json = format(event);
        assertThat(json.at("/log/level").asString()).isEqualTo("WARN");
        assertThat(json.at("/log/origin/function").asString()).isEqualTo("intercept");
        assertThat(json.at("/loresentry/project_id").asString()).isEqualTo("p-1");
        assertThat(json.at("/http/response/status_code").asLong()).isEqualTo(503L);
    }

    /** 지금 span 흉내. 줄을 쓰는 동안 이 span이 지금 문맥이다(운영에서는 agent나 로깅 팩토리가 연 span). */
    private static JsonNode inSpan(String traceId, String spanId, java.util.function.Supplier<JsonNode> write) {
        Span span = Span.wrap(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()));
        try (Scope ignored = span.makeCurrent()) {
            return write.get();
        }
    }

    @Test
    void writesTheSpanThatIsCurrentWhenTheLineIsWritten() {
        String flowTrace = "0af7651916cd43dd8448eb211c80319c";
        var bound = event(Level.INFO, "a", null);
        bound.setMDCPropertyMap(Map.of("trace.id", flowTrace, "span.id", "b7ad6b7169203331", "user.id", "u-1", "other", "not exported"));

        // 흐름과 같은 trace의 지금 span(호출을 감싸는 span 등)이 있으면 그 span이다
        JsonNode inner = inSpan(flowTrace, "00f067aa0ba902b7", () -> format(bound));
        assertThat(inner.at("/trace/id").asString()).isEqualTo(flowTrace);
        assertThat(inner.at("/span/id").asString()).isEqualTo("00f067aa0ba902b7");
        assertThat(inner.at("/user/id").asString()).isEqualTo("u-1");
        assertThat(inner.has("other")).isFalse();

        // 지금 span이 없으면 흐름의 값이다
        JsonNode none = format(bound);
        assertThat(none.at("/span/id").asString()).isEqualTo("b7ad6b7169203331");

        // 지금 span이 다른 trace면 흐름의 값이다(흐름의 줄이 다른 trace로 새지 않는다)
        JsonNode other = inSpan("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", () -> format(bound));
        assertThat(other.at("/trace/id").asString()).isEqualTo(flowTrace);
        assertThat(other.at("/span/id").asString()).isEqualTo("b7ad6b7169203331");

        // 흐름 밖의 프레임워크 줄(Kafka client 등)은 지금 span이다
        JsonNode framework = inSpan("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", () -> format(event(Level.INFO, "b", null)));
        assertThat(framework.at("/trace/id").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(framework.at("/span/id").asString()).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void writesContextFieldsFromMdcOnFrameworkLines() {
        // 프레임워크 줄: key-value가 없어도 AppLogger가 바인딩하며 MDC에 넣은 흐름의 문맥 필드가 붙는다.
        // MDC는 문자열만 담으므로 Kafka partition·offset은 숫자로 되돌려 쓴다(ES 타입이 long이다)
        var framework = event(Level.WARN, "framework line", null);
        framework.setMDCPropertyMap(Map.of("url.path", "/projects/p-1", "http.route", "/projects/{projectId}",
                "http.request.method", "GET", "loresentry.schedule.name", "outbox-relay", "loresentry.kafka.topic", "auth.user.deleted.v1",
                "loresentry.kafka.partition", "3", "loresentry.kafka.offset", "42", "loresentry.cloudevent.id", "e-1",
                "loresentry.project_id", "not a context key"));
        JsonNode json = format(framework);
        assertThat(json.at("/url/path").asString()).isEqualTo("/projects/p-1");
        assertThat(json.at("/http/route").asString()).isEqualTo("/projects/{projectId}");
        assertThat(json.at("/http/request/method").asString()).isEqualTo("GET");
        assertThat(json.at("/loresentry/schedule/name").asString()).isEqualTo("outbox-relay");
        assertThat(json.at("/loresentry/kafka/topic").asString()).isEqualTo("auth.user.deleted.v1");
        assertThat(json.at("/loresentry/kafka/partition").isNumber()).isTrue();
        assertThat(json.at("/loresentry/kafka/partition").asLong()).isEqualTo(3L);
        assertThat(json.at("/loresentry/kafka/offset").isNumber()).isTrue();
        assertThat(json.at("/loresentry/kafka/offset").asLong()).isEqualTo(42L);
        assertThat(json.at("/loresentry/cloudevent/id").asString()).isEqualTo("e-1");
        // 목록 밖의 MDC 값은 내보내지 않는다
        assertThat(json.at("/loresentry/project_id").isMissingNode()).isTrue();
    }

    @Test
    void lineKeyValuesWinOverMdcContextFieldsAndBadNumbersAreDropped() {
        // AppLogger 줄은 문맥 필드를 key-value로도 넘긴다. 둘이 다르면 그 줄에 넘긴 값이 남는다
        var line = event(Level.INFO, "d", null);
        line.addKeyValuePair(new KeyValuePair("url.path", "/from-line"));
        line.setMDCPropertyMap(Map.of("url.path", "/from-mdc", "loresentry.kafka.offset", "not-a-number"));
        JsonNode json = format(line);
        assertThat(json.at("/url/path").asString()).isEqualTo("/from-line");
        // 숫자로 되돌릴 수 없는 값은 그 필드만 빼고 줄은 그대로 쓴다
        assertThat(json.at("/loresentry/kafka/offset").isMissingNode()).isTrue();
        assertThat(json.get("message").asString()).isEqualTo("d");
    }

    @Test
    void writesTheExceptionWithoutItsMessageOnOneLine() {
        // 예외 메시지에는 SQL·받은 값이 섞일 수 있다. 원인 사슬의 클래스 이름과 프레임만 남는다(LOG_SCHEMA.md 4절)
        var failure = new IllegalStateException("boom select * from users", new IllegalArgumentException("inner secret"));
        JsonNode json = format(event(Level.ERROR, "request failed", failure));
        assertThat(json.at("/error/type").asString()).isEqualTo("java.lang.IllegalStateException");
        assertThat(json.get("error").has("message")).isFalse();
        assertThat(json.at("/error/stack_trace").asString()).startsWith("java.lang.IllegalStateException\n  at ")
                .contains("\njava.lang.IllegalArgumentException\n").doesNotContain("secret").doesNotContain("boom");
    }

    @Test
    void aWarningWithoutAnExceptionCarriesTheStackOfWhereItWasWritten() {
        // 예외 없는 warn·error도 로그만 보고 어느 경로에서 나왔는지 알 수 있다(LOG_SCHEMA.md 5절 "warn·error 스택"). 이 테스트 클래스도 로그
        // 패키지 안이라, 로그 패키지 밖의 프레임(JDK Optional.map)을 사이에 두고 불러 그 프레임이 첫 줄이 되는지 본다
        var ours = event(Level.WARN, "refresh request rejected", null);
        ours.addKeyValuePair(new KeyValuePair("error.type", "PROJECT_NOT_FOUND"));
        String line = java.util.Optional.of(ours).map(formatter::format).orElseThrow();
        String stack = JSON.readTree(line).at("/error/stack_trace").asString();
        assertThat(stack).startsWith("log site: PROJECT_NOT_FOUND\n  at ");
        assertThat(stack.lines().skip(1).findFirst().orElseThrow()).contains("java.util.Optional.map(");
        assertThat(stack).doesNotContain("ch.qos.logback").doesNotContain("EcsLogFormatter.");
        assertThat(stack.lines().count()).isLessThanOrEqualTo(31);

        // 프레임워크 줄(error.type 없음)은 logger 이름이 첫 줄이다
        assertThat(format(event(Level.ERROR, "framework failure", null)).at("/error/stack_trace").asString())
                .startsWith("log site: com.loresentry.content.Sample\n");
        // info 줄에는 붙이지 않는다
        assertThat(format(event(Level.INFO, "fine", null)).has("error")).isFalse();

        // 우리 실패 줄이 넘긴 메시지 없는 스택(LogField.safeStackTrace)이 남는다
        var failed = event(Level.WARN, "s3 call failed", null);
        failed.addKeyValuePair(new KeyValuePair("error.stack_trace", "java.io.IOException\n  at x.Y.z(Y.java:1)\n"));
        assertThat(format(failed).at("/error/stack_trace").asString()).isEqualTo("java.io.IOException\n  at x.Y.z(Y.java:1)\n");
    }

    @Test
    void keepsTheFirstValueWhenANameConflicts() {
        var event = event(Level.INFO, "c", null);
        event.addKeyValuePair(new KeyValuePair("log.level", "should not replace the object"));
        event.addKeyValuePair(new KeyValuePair("log", "should not replace the object either"));
        JsonNode json = format(event);
        assertThat(json.at("/log/level").asString()).isEqualTo("INFO");
    }
}
