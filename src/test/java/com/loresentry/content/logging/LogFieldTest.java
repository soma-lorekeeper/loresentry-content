package com.loresentry.content.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** LogField의 입력값·결과 규칙(LOG_SCHEMA.md 5절 "입력값과 결과")을 확인한다. 내용이 남지 않는지가 핵심이다. */
class LogFieldTest {
    private enum Verdict { CONFIRMED, DISMISSED }

    private static Map<String, Object> entries(LogField field) {
        Map<String, Object> out = new LinkedHashMap<>();
        field.entries().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @Test
    void stringsAreAlwaysMaskedWithTheirLength() {
        assertThat(entries(LogField.param("title", "유리 정원의 기록")))
                .containsExactly(Map.entry("loresentry.param.title", "[redacted]"), Map.entry("loresentry.param.title_length", 9L));
        assertThat(entries(LogField.result("summary", "본문"))).containsEntry("loresentry.result.summary", "[redacted]")
                .containsEntry("loresentry.result.summary_length", 2L);
    }

    @Test
    void scalarsStayAsTheyAre() {
        UUID folder = UUID.randomUUID();
        assertThat(entries(LogField.param("base_revision", 12))).containsExactly(Map.entry("loresentry.param.base_revision", 12));
        assertThat(entries(LogField.param("has_quote", true))).containsExactly(Map.entry("loresentry.param.has_quote", true));
        assertThat(entries(LogField.param("verdict", Verdict.DISMISSED))).containsExactly(Map.entry("loresentry.param.verdict", "DISMISSED"));
        assertThat(entries(LogField.param("target_folder_id", folder))).containsExactly(Map.entry("loresentry.param.target_folder_id", folder.toString()));
        assertThat(entries(LogField.result("updated_rows", 1L))).containsExactly(Map.entry("loresentry.result.updated_rows", 1L));
    }

    @Test
    void containersBecomeCountsAndJsonBecomesBytes() {
        assertThat(entries(LogField.param("drafts", List.of("a", "b", "c")))).containsExactly(Map.entry("loresentry.param.drafts_count", 3L));
        assertThat(entries(LogField.result("files", Map.of("x", 1)))).containsExactly(Map.entry("loresentry.result.files_count", 1L));
        var body = JsonMapper.builder().build().readTree("{\"text\":\"원고\"}");
        assertThat(entries(LogField.param("body", body))).containsKey("loresentry.param.body_bytes").doesNotContainKey("loresentry.param.body");
        assertThat(entries(LogField.param("body", new byte[] {1, 2, 3}))).containsExactly(Map.entry("loresentry.param.body_bytes", 3L));
    }

    @Test
    void codesOutsideTheAllowListBecomeOther() {
        Set<String> verdicts = Set.of("CONFIRMED", "DISMISSED");
        assertThat(entries(LogField.code("verdict", "DISMISSED", verdicts))).containsExactly(Map.entry("loresentry.param.verdict", "DISMISSED"));
        assertThat(entries(LogField.code("verdict", "제 이메일은 a@b.com 입니다", verdicts))).containsExactly(Map.entry("loresentry.param.verdict", "other"));
        assertThat(entries(LogField.resultCode("status", "SURPRISE", Set.of("CONFLICT")))).containsExactly(Map.entry("loresentry.result.status", "other"));
    }

    @Test
    void nullValuesLeaveNoField() {
        assertThat(LogField.param("title", (String) null)).isNull();
        assertThat(LogField.param("count", (Number) null)).isNull();
        assertThat(LogField.code("verdict", null, Set.of())).isNull();
        assertThat(LogField.route(null)).isNull();
    }

    @Test
    void routeIsTheOpenTelemetryName() {
        assertThat(entries(LogField.route("/projects/{projectId}"))).containsExactly(Map.entry("http.route", "/projects/{projectId}"));
    }

    @Test
    void kafkaAndCloudEventFieldsUseTheLoresentryNames() {
        UUID event = UUID.randomUUID();
        assertThat(entries(LogField.kafkaTopic("auth.user.deleted.v1"))).containsExactly(Map.entry("loresentry.kafka.topic", "auth.user.deleted.v1"));
        assertThat(entries(LogField.kafkaPartition(2))).containsExactly(Map.entry("loresentry.kafka.partition", 2L));
        assertThat(entries(LogField.kafkaOffset(42L))).containsExactly(Map.entry("loresentry.kafka.offset", 42L));
        assertThat(entries(LogField.cloudeventId(event))).containsExactly(Map.entry("loresentry.cloudevent.id", event.toString()));
        assertThat(LogField.cloudeventId(null)).isNull();
    }

    @Test
    void theSafeStackTraceKeepsClassesAndFramesButNotMessages() {
        var failure = new IllegalStateException("select password from users", new IllegalArgumentException("a@b.com"));
        String trace = (String) entries(LogField.safeStackTrace(failure)).get("error.stack_trace");
        assertThat(trace).contains("java.lang.IllegalStateException").contains("java.lang.IllegalArgumentException").contains("  at ")
                .doesNotContain("select password").doesNotContain("a@b.com");
    }
}
