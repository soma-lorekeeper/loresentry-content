package com.loresentry.content.inbox;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.support.KafkaTestSupport;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Kafka 로 받은 이벤트가 보낸 쪽의 trace 를 잇는지 확인한다(LOG_SCHEMA.md 7절 Kafka 소비·outbox).
 *
 * <p>auth 의 회원 탈퇴 요청(trace T)이 outbox 행에 traceparent 를 저장하고, relay 가 그 행의 범위(trace T 를 잇는 span) 안에서 보낸 상황을
 * 흉내 낸다. 헤더는 Kafka 발행 계측(테스트에서는 AgentLikeTracing)이 보내는 순간의 지금 문맥으로 넣는다.
 * <ul>
 *   <li>content listener 가 그 레코드를 처리하는 줄(listener → service → repository)이 모두 trace T 와 topic·offset·이벤트 ID 를 갖는다</li>
 *   <li>그 처리가 content outbox 에 쓴 ProjectDeleted 행에도 trace T 가 저장된다. 그래서 다음 서비스(graph-rag)까지 같은 trace 로 이어진다</li>
 * </ul>
 */
@TestPropertySource(properties = "kafka.consumer.enabled.user-deleted=true")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class InboxTraceTest extends KafkaTestSupport {
    private static final String USER_DELETED = "auth.user.deleted.v1";
    private static final String AUTH_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void aDeletedUserKeepsTheAuthTraceThroughTheListenerAndIntoTheOutbox(CapturedOutput output) throws Exception {
        UUID user = UUID.randomUUID();
        mockMvc.perform(as(body(post("/projects"), "{\"name\":\"trace test\"}"), user)).andExpect(status().isCreated());
        UUID eventId = UUID.randomUUID();

        ProducerRecord<String, String> record = new ProducerRecord<>(USER_DELETED, user.toString(),
                "{\"user_id\": \"" + user + "\"}");
        header(record, "ce_specversion", "1.0");
        header(record, "ce_id", eventId.toString());
        header(record, "ce_type", "UserDeleted");
        header(record, "ce_source", "/auth");
        header(record, "ce_subject", user.toString());
        header(record, "ce_time", OffsetDateTime.now(ZoneOffset.UTC).toString());
        header(record, "content-type", "application/json");
        // auth relay 의 행 범위 흉내: trace T 를 지금 문맥으로 두고 보낸다. 발행 계측이 그 문맥으로 traceparent 헤더를 넣는다
        Span authRow = Span.wrap(SpanContext.createFromRemoteParent(AUTH_TRACE, "00f067aa0ba902b7", TraceFlags.getSampled(),
                TraceState.getDefault()));
        try (Scope ignored = authRow.makeCurrent()) {
            kafkaTemplate.send(record).get();
        }

        // 처리가 끝나 ProjectDeleted 가 outbox 에 쓰일 때까지 기다린다
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(outboxTraceparents()).hasSize(1));
        assertThat(outboxTraceparents().getFirst()).startsWith("00-" + AUTH_TRACE + "-").hasSize(55);

        List<JsonNode> lines = output.getOut().lines().filter(line -> line.startsWith("{")).map(json::readTree)
                .filter(line -> AUTH_TRACE.equals(line.at("/trace/id").asString())).toList();
        assertThat(lines).extracting(line -> line.get("message").asString())
                .contains("user deleted event received", "inbox event run once", "outbox events append");
        JsonNode received = lines.stream().filter(line -> "user deleted event received".equals(line.get("message").asString()))
                .findFirst().orElseThrow();
        assertThat(received.at("/log/layer").asString()).isEqualTo("consumer");
        assertThat(received.at("/loresentry/kafka/topic").asString()).isEqualTo(USER_DELETED);
        assertThat(received.at("/loresentry/kafka/offset").isNumber()).isTrue();
        assertThat(received.at("/loresentry/cloudevent/id").asString()).isEqualTo(eventId.toString());
        // 보낸 쪽 outbox 줄과 같은 cloudevent 필드: 종류(처리하는 종류라 그대로)와 대상
        assertThat(received.at("/loresentry/cloudevent/type").asString()).isEqualTo("UserDeleted");
        assertThat(received.at("/loresentry/cloudevent/subject").asString()).isEqualTo(user.toString());

        // 종료 줄: 결과(outcome)와 걸린 시간, 진행 중에 알게 된 대상(삭제된 사용자)이 붙는다
        JsonNode processed = lines.stream().filter(line -> "user deleted event processed".equals(line.get("message").asString()))
                .findFirst().orElseThrow();
        assertThat(processed.at("/log/level").asString()).isEqualTo("INFO");
        assertThat(processed.at("/loresentry/result/outcome").asString()).isEqualTo("PROCESSED");
        assertThat(processed.at("/event/duration").asLong()).isPositive();
        assertThat(processed.at("/user/id").asString()).isEqualTo(user.toString());
        assertThat(processed.at("/loresentry/kafka/topic").asString()).isEqualTo(USER_DELETED);
    }

    private List<String> outboxTraceparents() {
        return jdbcClient.sql("select traceparent from outbox_events where event_type = 'ProjectDeleted'")
                .query(String.class).list();
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
