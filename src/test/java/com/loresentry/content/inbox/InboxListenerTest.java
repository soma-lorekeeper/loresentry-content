package com.loresentry.content.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.refresh.GraphRefreshCompletedEvent;
import com.loresentry.content.refresh.GraphRefreshCompletedEvent.Outcome;
import com.loresentry.content.refresh.GraphRefreshCompletedEvent.Result;
import com.loresentry.content.refresh.GraphRefreshService;
import com.loresentry.content.support.KafkaTestSupport;
import com.loresentry.content.user.UserDeletedEvent;
import com.loresentry.content.user.UserDeletedService;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * listener 를 켠 앱에 실제 Kafka 로 메시지를 보내, 받은 것이 처리 기록과 함께 Service 까지 가는지 확인한다. topic 이름,
 * 설정에서 읽는 listener id 와 group id, 켜고 끄는 설정이 실제로 맞물리는지는 broker 로 보내 봐야 알 수 있다.
 *
 * <p>Service 둘은 {@code @MockitoBean} 으로 바꿔 끼워, 불렸는지와 몇 번 불렸는지만 센다. 진짜 Service 의
 * {@code MANDATORY} 와 처리 기록의 rollback 은 각자의 테스트(Service 테스트, {@link InboxTransactionTest})가 본다.
 * mock 은 테스트마다 새로 비워진다.
 *
 * <p>{@code @TestPropertySource} 는 이 테스트에서만 두 listener 를 켠다. {@code @DirtiesContext} 는 이 테스트가 끝나면
 * 그 앱을 닫는다 — 살려 두면 켜진 listener 가 뒤에서 계속 돌아 다른 테스트에 섞인다.
 *
 * <p>"한 번만"과 "건너뜀"은 표지 메시지로 확인한다. 같은 key 의 메시지는 같은 partition 에 실려 차례로 처리되므로,
 * 마지막에 보낸 표지가 처리됐다면 앞의 메시지도 처리가 끝난 것이다.
 */
@TestPropertySource(properties = {
        "kafka.consumer.enabled.user-deleted=true",
        "kafka.consumer.enabled.graph-refresh-completed=true"})
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class InboxListenerTest extends KafkaTestSupport {

    /** 설정(kafka.consumer.topics.*)의 값과 따로 적어, 그 값이 틀리면 테스트가 알아채게 한다. */
    private static final String USER_DELETED = "auth.user.deleted.v1";

    private static final String GRAPH_REFRESH_COMPLETED = "graph-rag.graph-refresh.completed.v1";

    /** 설정의 group id 이자 처리 기록의 consumer_name. 설정 값과 따로 적는 이유는 위와 같다. */
    private static final String USER_DELETED_CONSUMER = "content.user-deleted";

    private static final String GRAPH_REFRESH_COMPLETED_CONSUMER = "content.graph-refresh-completed";

    @MockitoBean
    UserDeletedService userDeleted;

    @MockitoBean
    GraphRefreshService graphRefresh;

    /** 앱 안의 listener 들을 id 로 찾는 곳. */
    @Autowired
    KafkaListenerEndpointRegistry registry;

    /**
     * 계약의 필수 header 일곱 개를 모두 붙여 보내고, broker 가 받았다고 답할 때까지 기다린다.
     *
     * @param subject header {@code ce_subject}. payload 의 대상 id 와 같아야 받아들여진다
     */
    private void send(String topic, String key, UUID eventId, String type, String source, UUID subject,
                      String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        header(record, "ce_specversion", "1.0");
        header(record, "ce_id", eventId.toString());
        header(record, "ce_type", type);
        header(record, "ce_source", source);
        header(record, "ce_subject", subject.toString());
        header(record, "ce_time", OffsetDateTime.now(ZoneOffset.UTC).toString());
        header(record, "content-type", "application/json");
        // get() 으로 broker 의 확인을 기다린다. 보내기 자체가 실패하면 여기서 테스트가 멈춘다.
        kafkaTemplate.send(record).get();
    }

    /** header 하나를 계약대로 UTF-8 글자로 붙인다. */
    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    /** authentication 서비스가 보내는 모양으로 UserDeleted 를 보낸다. key 와 대상은 유저 id 다. */
    private void sendUserDeleted(UUID eventId, String type, UUID userId) throws Exception {
        send(USER_DELETED, userId.toString(), eventId, type, "/auth", userId, "{\"user_id\": \"" + userId + "\"}");
    }

    /** 이 처리기와 이벤트의 처리 기록이 몇 개 있는지 센다. */
    private long recorded(String consumerName, UUID eventId) {
        return jdbcClient.sql("select count(*) from inbox_events where consumer_name = :consumer and event_id = :event")
                .param("consumer", consumerName)
                .param("event", eventId)
                .query(Long.class)
                .single();
    }

    /**
     * 처리 기록이 생길 때까지 기다린다. 기록은 Service 가 돌아온 뒤 commit 되므로, 기록이 보이면 Service 는 이미
     * 불렸다. 처음에는 listener 가 group 에 합류하는 시간이 있어 넉넉히 기다린다.
     */
    private void awaitRecorded(String consumerName, UUID eventId) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(recorded(consumerName, eventId)).isEqualTo(1));
    }

    @Test
    void handsAUserDeletionToItsService() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        sendUserDeleted(eventId, "UserDeleted", userId);

        awaitRecorded(USER_DELETED_CONSUMER, eventId);
        verify(userDeleted).handle(new UserDeletedEvent(userId));
    }

    @Test
    void handsAFinishedGraphRefreshToItsService() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String resultKey = "refresh/" + projectId + "/" + requestId + "/result.json";
        String payload = """
                {"request_id": "%s", "project_id": "%s", "outcome": "SUCCEEDED",
                 "result": {"bucket": "lore-refresh", "key": "%s"}, "error": null, "prompt_version": "v1"}
                """.formatted(requestId, projectId, resultKey);

        // key 는 프로젝트 id, 대상(ce_subject)은 요청 id 다. 계약이 그렇게 정했다.
        send(GRAPH_REFRESH_COMPLETED, projectId.toString(), eventId, "GraphRefreshCompleted", "/graph-rag", requestId,
                payload);

        awaitRecorded(GRAPH_REFRESH_COMPLETED_CONSUMER, eventId);
        // 결과 파일을 먼저 가져오고(트랜잭션 밖), 그것을 처리 기록과 함께 반영한다.
        GraphRefreshCompletedEvent expected = new GraphRefreshCompletedEvent(requestId, projectId, Outcome.SUCCEEDED,
                new Result("lore-refresh", resultKey), null, "v1");
        verify(graphRefresh).fetchResult(expected);
        verify(graphRefresh).complete(eq(expected), any());
    }

    /** relay 가 같은 outbox 행을 다시 보낸 경우다. 두 번째 것은 Service 까지 가지 않고 한 줄만 남는다. */
    @Test
    void handlesTheSameEventOnlyOnce(CapturedOutput output) throws Exception {
        UUID duplicated = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        sendUserDeleted(duplicated, "UserDeleted", userId);
        sendUserDeleted(duplicated, "UserDeleted", userId);
        sendUserDeleted(marker, "UserDeleted", userId);

        awaitRecorded(USER_DELETED_CONSUMER, marker);
        // 처음 온 것 한 번과 표지 한 번이다. 다시 온 것까지 불렸다면 세 번이다.
        verify(userDeleted, times(2)).handle(new UserDeletedEvent(userId));
        assertThat(recorded(USER_DELETED_CONSUMER, duplicated)).isEqualTo(1);
        assertThat(output.getOut())
                .contains("inbox event already processed: consumer=" + USER_DELETED_CONSUMER + ", event=" + duplicated);
    }

    /** topic 에 우리가 처리하지 않는 종류가 오면 건너뛴다. 처리 기록도 남기지 않는다. */
    @Test
    void skipsAKindItDoesNotHandle(CapturedOutput output) throws Exception {
        UUID unknown = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        sendUserDeleted(unknown, "UserRenamed", userId);
        sendUserDeleted(marker, "UserDeleted", userId);

        awaitRecorded(USER_DELETED_CONSUMER, marker);
        // 표지 한 번뿐이다.
        verify(userDeleted, times(1)).handle(new UserDeletedEvent(userId));
        assertThat(recorded(USER_DELETED_CONSUMER, unknown)).isZero();
        assertThat(output.getOut()).contains("inbox event skipped: topic=" + USER_DELETED).contains("type=UserRenamed");
    }

    /** 설정에서 읽은 listener id 로 두 listener 를 찾을 수 있고, 같은 값이 group id 로 쓰이며, 켜 둔 대로 돌고 있다. */
    @Test
    void runsBothListenersUnderTheirGroupIds() {
        for (String id : List.of(USER_DELETED_CONSUMER, GRAPH_REFRESH_COMPLETED_CONSUMER)) {
            MessageListenerContainer container = registry.getListenerContainer(id);
            assertThat(container).isNotNull();
            assertThat(container.getGroupId()).isEqualTo(id);
            assertThat(container.isRunning()).isTrue();
        }
    }
}
