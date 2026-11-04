package com.loresentry.content.outbox;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * outbox 행을 Kafka 메시지로 옮긴다. 팀 Kafka 설계 문서의 "Message 계약"이 정한 topic, key, header 가
 * 이 클래스 한 곳에 모여 있다.
 *
 * <p>CloudEvents 1.0 Kafka binding 의 binary mode 다. value 에는 이벤트 데이터만 넣고 이벤트에 대한 정보는
 * header 로 보낸다. 소비자는 3MB 짜리 value 를 파싱하기 전에 header 만 보고 세 가지를 가릴 수 있다:
 * 이 이벤트를 이미 처리했는가({@code ce_id}), 어떤 종류의 이벤트인가({@code ce_type}), 같은 대상에 대해 이미
 * 더 최신 것을 반영했는가({@code ce_subject} 와 {@code ce_sequence}).
 *
 * <p>무엇이 어떻게 바뀌었는지(생성인지 수정인지, 제목인지 본문인지)는 header 에 없다. 그것은 value 안에 있다.
 */
final class OutboxMessages {

    static final String PROJECT_CHANGED = "content.project.changed.v2";

    static final String GRAPH_REFRESH_REQUESTED = "content.graph-refresh.requested.v1";

    /** 계약의 시각 표기. RFC 3339 UTC 이고 밀리초 세 자리를 항상 적는다. */
    private static final DateTimeFormatter CE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private OutboxMessages() {
    }

    /**
     * 이벤트 종류가 갈 topic. topic 은 행에 적어 두지 않고 여기서 정한다 — 계약이 새 버전 topic 으로 옮길 때
     * 같은 행을 두 topic 에 보내도록 정해 두었으므로, topic 은 행의 성질이 아니다.
     */
    static String topicOf(String eventType) {
        return switch (eventType) {
            case "FileChanged", "ProjectDeleted" -> PROJECT_CHANGED;
            case "GraphRefreshRequested" -> GRAPH_REFRESH_REQUESTED;
            default -> throw new IllegalArgumentException("unknown event type: " + eventType);
        };
    }

    static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topicOf(event.eventType()), event.eventKey(), event.payload());
        header(record, "ce_specversion", "1.0");
        header(record, "ce_id", event.id().toString());
        header(record, "ce_type", event.eventType());
        header(record, "ce_source", "/content");
        // 이벤트의 대상. payload 에도 같은 id 가 있지만, 소비자가 value 를 열지 않고 대상을 알 수 있게 꺼내 싣는다.
        header(record, "ce_subject", event.aggregateId().toString());
        header(record, "ce_time", CE_TIME.format(event.createdAt()));
        // 종류를 가리지 않고 모든 행에 붙인다. 계약이 요구하는 곳은 content.project.changed.v1 뿐이지만,
        // 종류를 보고 가려 붙이면 relay 가 이벤트의 뜻을 알아야 한다. 쓰지 않는 소비자는 무시하면 된다.
        header(record, "ce_sequence", Long.toString(event.seq()));
        header(record, "content-type", "application/json");
        // traceparent 는 여기서 넣지 않는다. Kafka 발행 계측(EDOT agent)이 보내는 순간의 지금 문맥으로 넣고, 같은 이름의 헤더가
        // 있으면 지우고 다시 넣는다. OutboxPublisher 가 행에 저장된 traceparent 의 범위 안에서 보내므로 그 값이 요청의 trace 를 잇는다
        // (LOG_SCHEMA.md 7절 "Kafka 발행").
        return record;
    }

    private static void header(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
