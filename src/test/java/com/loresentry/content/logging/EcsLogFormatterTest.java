package com.loresentry.content.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
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

    @Test
    void prefersAppLoggerTraceAndFallsBackToTheCurrentSpan() {
        var bound = event(Level.INFO, "a", null);
        bound.setMDCPropertyMap(Map.of("trace.id", "t-app", "span.id", "s-app", "traceId", "t-otel", "spanId", "s-otel",
                "user.id", "u-1", "other", "not exported"));
        JsonNode json = format(bound);
        assertThat(json.at("/trace/id").asString()).isEqualTo("t-app");
        assertThat(json.at("/span/id").asString()).isEqualTo("s-app");
        assertThat(json.at("/user/id").asString()).isEqualTo("u-1");
        assertThat(json.has("other")).isFalse();
        assertThat(json.has("traceId")).isFalse();

        var framework = event(Level.INFO, "b", null);
        framework.setMDCPropertyMap(Map.of("traceId", "t-otel", "spanId", "s-otel"));
        JsonNode fallback = format(framework);
        assertThat(fallback.at("/trace/id").asString()).isEqualTo("t-otel");
        assertThat(fallback.at("/span/id").asString()).isEqualTo("s-otel");
    }

    @Test
    void writesTheExceptionAsErrorFieldsOnOneLine() {
        JsonNode json = format(event(Level.ERROR, "request failed", new IllegalStateException("boom")));
        assertThat(json.at("/error/type").asString()).isEqualTo("java.lang.IllegalStateException");
        assertThat(json.at("/error/message").asString()).isEqualTo("boom");
        assertThat(json.at("/error/stack_trace").asString()).contains("IllegalStateException").contains("\tat ");
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
