package com.loresentry.content.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

class OutboxMessagesTest {

    private static final UUID EVENT_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");

    private static final String PROJECT_ID = "0199a1b2-0000-7000-8000-000000000001";

    private static final UUID FILE_ID = UUID.fromString("0199a1b2-0000-7000-8000-000000000002");

    private static OutboxEvent event(String eventType, String createdAt, String traceparent) {
        return new OutboxEvent(EVENT_ID, 1042, eventType, PROJECT_ID, FILE_ID,
                "{\"project_id\":\"" + PROJECT_ID + "\"}", OffsetDateTime.parse(createdAt), traceparent);
    }

    private static OutboxEvent event(String eventType) {
        return event(eventType, "2026-09-30T10:00:00.123Z", null);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    /**
     * Kafka 는 같은 topic, 같은 key 안에서만 순서를 지킨다. 프로젝트 삭제가 다른 topic 으로 가면 밀려 있던
     * 파일 변경이 삭제보다 늦게 처리되어, 지운 프로젝트의 데이터가 소비자 쪽에 다시 생긴다.
     */
    @Test
    void sendsFileChangesAndProjectDeletionsToTheSameTopic() {
        assertThat(OutboxMessages.topicOf("FileChanged")).isEqualTo("content.project.changed.v2");
        assertThat(OutboxMessages.topicOf("ProjectDeleted")).isEqualTo("content.project.changed.v2");
    }

    @Test
    void sendsGraphRefreshRequestsToTheirOwnTopic() {
        assertThat(OutboxMessages.topicOf("GraphRefreshRequested"))
                .isEqualTo("content.graph-refresh.requested.v1");
    }

    /** 모르는 종류를 아무 topic 으로나 보내면 소비자가 받아서는 안 될 것을 받는다. 고르지 않고 멈춘다. */
    @Test
    void refusesAnEventTypeTheContractDoesNotName() {
        assertThatThrownBy(() -> OutboxMessages.topicOf("FileRenamed"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsTheRecordTheContractDescribes() {
        ProducerRecord<String, String> record = OutboxMessages.toRecord(event("FileChanged"));

        assertThat(record.topic()).isEqualTo("content.project.changed.v2");
        assertThat(record.key()).isEqualTo(PROJECT_ID);
        assertThat(record.value()).isEqualTo("{\"project_id\":\"" + PROJECT_ID + "\"}");
        // partition 은 비워 둔다. 비어 있어야 key 로 정해지고, 같은 프로젝트가 같은 partition 으로 간다.
        assertThat(record.partition()).isNull();

        assertThat(header(record, "ce_specversion")).isEqualTo("1.0");
        assertThat(header(record, "ce_id")).isEqualTo(EVENT_ID.toString());
        assertThat(header(record, "ce_type")).isEqualTo("FileChanged");
        assertThat(header(record, "ce_source")).isEqualTo("/content");
        // key 는 순서를 지키는 단위(프로젝트)이고 subject 는 이벤트의 대상(파일)이다. 둘은 다른 값이다.
        assertThat(header(record, "ce_subject")).isEqualTo(FILE_ID.toString());
        assertThat(header(record, "ce_time")).isEqualTo("2026-09-30T10:00:00.123Z");
        assertThat(header(record, "ce_sequence")).isEqualTo("1042");
        assertThat(header(record, "content-type")).isEqualTo("application/json");
    }

    /**
     * 소비자는 이 값으로 같은 파일의 이벤트 가운데 더 최신인 것을 가리고, 프로젝트 삭제보다 먼저 일어난 파일 변경이
     * 늦게 도착했을 때 그것을 버린다. 파일 변경과 프로젝트 삭제가 같은 순번을 써야 둘을 비교할 수 있다.
     *
     * <p>그래프 최신화 요청에는 계약상 필요 없지만 붙여 보낸다. 종류를 가려 붙이지 않기 위해서다.
     */
    @Test
    void carriesTheOutboxSequenceOnEveryEvent() {
        for (String eventType : new String[] { "FileChanged", "ProjectDeleted", "GraphRefreshRequested" }) {
            assertThat(header(OutboxMessages.toRecord(event(eventType)), "ce_sequence")).isEqualTo("1042");
        }
    }

    /** DB 의 시각은 마이크로초까지 있고 시간대가 붙어 온다. 계약은 UTC 에 밀리초 세 자리다. */
    @Test
    void writesTheTimeInUtcWithExactlyThreeFractionDigits() {
        assertThat(header(OutboxMessages.toRecord(event("FileChanged", "2026-09-30T19:00:00.123456+09:00", null)),
                "ce_time")).isEqualTo("2026-09-30T10:00:00.123Z");
        // 밀리초가 0 이어도 자리를 지운 채로 보내지 않는다.
        assertThat(header(OutboxMessages.toRecord(event("FileChanged", "2026-09-30T10:00:00Z", null)),
                "ce_time")).isEqualTo("2026-09-30T10:00:00.000Z");
    }

    @Test
    void addsTheTraceHeaderOnlyWhenTheRowHasOne() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        assertThat(OutboxMessages.toRecord(event("FileChanged")).headers().lastHeader("traceparent")).isNull();
        assertThat(header(OutboxMessages.toRecord(event("FileChanged", "2026-09-30T10:00:00.123Z", traceparent)),
                "traceparent")).isEqualTo(traceparent);
    }
}
