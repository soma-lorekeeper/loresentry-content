package com.loresentry.content.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.loresentry.content.refresh.GraphRefreshService;
import com.loresentry.content.support.KafkaTestSupport;
import com.loresentry.content.user.UserDeletedEvent;
import com.loresentry.content.user.UserDeletedService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 처리하지 못한 메시지가 계약대로 DLQ 로 가는지 실제 Kafka 에서 확인한다. 계약에 맞지 않는 메시지는 곧바로, 처리가
 * 실패하는 메시지는 다시 시도한 뒤에 간다. DLQ 에 붙는 header 와 남는 로그에 예외 메시지가 새지 않는지도 본다.
 *
 * <p>Service 둘은 {@code @MockitoBean} 으로 바꿔 끼워 실패를 일부러 만든다. 다시 시도하는 간격은
 * {@code @TestBean} 으로 "간격 없이 2번 더"로 바꾼다 — 운영의 간격(합 25초)을 테스트마다 기다리지 않기 위해서다.
 * 운영의 간격 자체는 {@link InboxBackOffTest} 가 확인한다.
 *
 * <p>{@code @TestPropertySource} 로 두 listener 를 켜고, {@code @DirtiesContext} 로 이 테스트가 끝나면 그 앱을 닫는다.
 */
@TestPropertySource(properties = {
        "kafka.consumer.enabled.user-deleted=true",
        "kafka.consumer.enabled.graph-refresh-completed=true"})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class InboxErrorHandlingTest extends KafkaTestSupport {

    /** main 의 설정과 따로 적어, 그 값이 틀리면 테스트가 알아채게 한다. */
    private static final String USER_DELETED = "auth.user.deleted.v1";

    private static final String USER_DELETED_DLQ = "auth.user.deleted.v1.content.dlq";

    private static final String GRAPH_REFRESH_COMPLETED = "graph-rag.graph-refresh.completed.v1";

    private static final String GRAPH_REFRESH_COMPLETED_DLQ = "graph-rag.graph-refresh.completed.v1.content.dlq";

    /** 예외 메시지에 넣는 글자. DLQ 와 로그 어디에도 나오면 안 된다. */
    private static final String SECRET = "민감한 내용";

    @MockitoBean
    UserDeletedService userDeleted;

    @MockitoBean
    GraphRefreshService graphRefresh;

    /** 운영의 간격 대신 쓸 것. 아래 같은 이름의 static 메서드가 만든다. */
    @TestBean(name = "inboxBackOff")
    BackOff inboxBackOff;

    /** 간격 없이 2번 더 시도한다. 첫 시도까지 모두 세 번이다. */
    static BackOff inboxBackOff() {
        return new FixedBackOff(0, 2);
    }

    /** 계약의 필수 header 일곱 개를 모두 붙여 보내고, broker 가 받았다고 답할 때까지 기다린다. */
    private void send(String topic, String key, UUID eventId, String type, String source, UUID subject,
                      String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add("ce_specversion", bytes("1.0"));
        record.headers().add("ce_id", bytes(eventId.toString()));
        record.headers().add("ce_type", bytes(type));
        record.headers().add("ce_source", bytes(source));
        record.headers().add("ce_subject", bytes(subject.toString()));
        record.headers().add("ce_time", bytes(OffsetDateTime.now(ZoneOffset.UTC).toString()));
        record.headers().add("content-type", bytes("application/json"));
        kafkaTemplate.send(record).get();
    }

    /** header 값은 계약대로 UTF-8 글자다. */
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** header 를 글자로 읽는다. 없으면 null. */
    private static String text(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** 이 이벤트의 처리 기록이 몇 개 있는지 센다. 처리기는 가리지 않는다. */
    private long recorded(UUID eventId) {
        return jdbcClient.sql("select count(*) from inbox_events where event_id = :event")
                .param("event", eventId)
                .query(Long.class)
                .single();
    }

    /**
     * 계약에 맞지 않는 메시지(ce_subject 와 payload 의 유저가 다름)는 다시 시도하지 않고 곧바로 DLQ 로 간다. DLQ
     * 메시지는 원래 내용과 header 를 그대로 갖고, 계약이 정한 실패 정보만 덧붙인다.
     */
    @Test
    void sendsAContractViolationStraightToTheDeadLetterTopic(CapturedOutput output) throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String payload = "{\"user_id\": \"" + userId + "\"}";

        // 대상(ce_subject)을 다른 유저로 적는다.
        send(USER_DELETED, userId.toString(), eventId, "UserDeleted", "/auth", UUID.randomUUID(), payload);

        // key 가 그대로라 DLQ 에서도 같은 key 로 찾는다.
        ConsumerRecord<String, String> dead = awaitRecords(USER_DELETED_DLQ, userId.toString(), 1).getFirst();
        // 원래 내용과 원래 header 는 그대로다.
        assertThat(dead.value()).isEqualTo(payload);
        assertThat(text(dead, "ce_id")).isEqualTo(eventId.toString());
        assertThat(text(dead, "ce_type")).isEqualTo("UserDeleted");
        // 원래 위치. partition 과 offset 은 글자가 아니라 int, long 의 바이트다(계약).
        assertThat(text(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(USER_DELETED);
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).hasSize(Integer.BYTES);
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).hasSize(Long.BYTES);
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP).value()).hasSize(Long.BYTES);
        assertThat(text(dead, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP_TYPE)).isEqualTo("CreateTime");
        // 실패 정보. 계약 위반은 어느 검사에 걸렸는지를 적는다.
        assertThat(text(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).isEqualTo(InvalidEventException.class.getName());
        assertThat(text(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).isEqualTo(InvalidEventException.class.getName());
        assertThat(text(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isEqualTo("ce_subject does not match the payload id");
        // 계약에 없는 것은 붙이지 않는다.
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNull();
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isNull();

        // 처리로 넘어가지 않았고, 처리 기록도 없다.
        verify(userDeleted, never()).handle(any());
        assertThat(recorded(eventId)).isZero();
        // 한 번 실패하고 곧바로 DLQ 로 갔다. 로그는 DLQ 에 보인 직후에 찍힐 수 있어 잠시 기다린다.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut())
                .contains("event=" + eventId + ", attempt=1, reason=" + InvalidEventException.class.getName())
                .contains("inbox event dead-lettered: topic=" + USER_DELETED)
                .contains("event=" + eventId + ", reason=" + InvalidEventException.class.getName())
                .doesNotContain("event=" + eventId + ", attempt=2"));
    }

    /**
     * 처리가 계속 실패하면 다시 시도한 뒤 DLQ 로 간다. 예외의 메시지는 DLQ 에도 로그에도 남지 않고 클래스 이름만 남는다.
     * 처리 기록은 매번 rollback 되어 남지 않는다.
     */
    @Test
    void sendsAMessageWhoseHandlingKeepsFailingToTheDeadLetterTopicAfterRetrying(CapturedOutput output)
            throws Exception {
        doThrow(new IllegalStateException(SECRET)).when(userDeleted).handle(any());
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        send(USER_DELETED, userId.toString(), eventId, "UserDeleted", "/auth", userId,
                "{\"user_id\": \"" + userId + "\"}");

        ConsumerRecord<String, String> dead = awaitRecords(USER_DELETED_DLQ, userId.toString(), 1).getFirst();
        // 껍데기(ListenerExecutionFailedException)가 아니라 실제로 던진 예외다. 원인이 없어 둘이 같다.
        assertThat(text(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).isEqualTo(IllegalStateException.class.getName());
        assertThat(text(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).isEqualTo(IllegalStateException.class.getName());
        // 계약 위반이 아니므로 메시지 자리에 클래스 이름을 적는다.
        assertThat(text(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).isEqualTo(IllegalStateException.class.getName());
        // 어느 header 에도 예외의 메시지가 없다.
        for (Header header : dead.headers()) {
            assertThat(new String(header.value(), StandardCharsets.UTF_8)).doesNotContain(SECRET);
        }

        // 첫 시도와 다시 시도한 두 번, 모두 세 번 불렸다. 처리 기록은 매번 rollback 되었다.
        verify(userDeleted, times(3)).handle(new UserDeletedEvent(userId));
        assertThat(recorded(eventId)).isZero();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(output.getOut())
                .contains("event=" + eventId + ", attempt=1, reason=" + IllegalStateException.class.getName())
                .contains("event=" + eventId + ", attempt=3, reason=" + IllegalStateException.class.getName())
                .contains("event=" + eventId + ", reason=" + IllegalStateException.class.getName())
                .doesNotContain(SECRET));
    }

    /** 한 번 실패한 처리가 다시 시도에서 성공하면 그대로 처리되고 처리 기록이 남는다. DLQ 로 가지 않는다. */
    @Test
    void handlesAMessageWhoseFirstAttemptFailed(CapturedOutput output) throws Exception {
        doThrow(new IllegalStateException(SECRET)).doNothing().when(userDeleted).handle(any());
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        send(USER_DELETED, userId.toString(), eventId, "UserDeleted", "/auth", userId,
                "{\"user_id\": \"" + userId + "\"}");

        // 두 번째 시도에서 처리되어 기록이 생긴다.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(recorded(eventId)).isEqualTo(1));
        verify(userDeleted, times(2)).handle(new UserDeletedEvent(userId));
        assertThat(output.getOut())
                .contains("event=" + eventId + ", attempt=1, reason=" + IllegalStateException.class.getName())
                // DLQ 로 보냈다는 줄은 없다.
                .doesNotContain("event=" + eventId + ", reason=");
    }

    /** GraphRefreshCompleted 는 자기 topic 의 DLQ 로 간다. 소비자마다, topic 마다 DLQ 가 따로다. */
    @Test
    void sendsAGraphRefreshResultToItsOwnDeadLetterTopic() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        // outcome 이 계약에 없는 값이라 읽기에서 거부된다.
        String payload = """
                {"request_id": "%s", "project_id": "%s", "outcome": "UNKNOWN", "result": null, "error": null,
                 "prompt_version": "v1"}
                """.formatted(requestId, projectId);

        send(GRAPH_REFRESH_COMPLETED, projectId.toString(), eventId, "GraphRefreshCompleted", "/graph-rag", requestId,
                payload);

        ConsumerRecord<String, String> dead =
                awaitRecords(GRAPH_REFRESH_COMPLETED_DLQ, projectId.toString(), 1).getFirst();
        assertThat(text(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(GRAPH_REFRESH_COMPLETED);
        assertThat(text(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).isEqualTo(InvalidEventException.class.getName());
        assertThat(text(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).startsWith("unreadable payload: ");
        verify(graphRefresh, never()).complete(any());
    }
}
