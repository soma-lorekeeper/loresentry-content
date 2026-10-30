package com.loresentry.content.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.loresentry.content.support.KafkaTestSupport;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.Test;

/**
 * application.yaml 의 Kafka 설정이 실제 broker 에서 통하는지 본다. 설정 이름을 틀리면 Spring 은 오류 없이
 * 무시하므로, 값이 전달되는 것과 그 값으로 실제로 보내고 받을 수 있는 것은 따로 확인해야 한다.
 */
class KafkaSettingsTest extends KafkaTestSupport {

    private static final String PROJECT_CHANGED = "content.project.changed.v2";

    @Test
    void sendsARecordAndReadsItBackUnchanged() throws Exception {
        String key = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();
        String value = "{\"title\":\"도깨비 학살\"}";
        ProducerRecord<String, String> record = new ProducerRecord<>(PROJECT_CHANGED, key, value);
        record.headers().add("ce_id", eventId.getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, String> received = awaitRecords(PROJECT_CHANGED, key, 1).getFirst();
        assertThat(received.value()).isEqualTo(value);
        assertThat(new String(received.headers().lastHeader("ce_id").value(), StandardCharsets.UTF_8))
                .isEqualTo(eventId);
    }

    /**
     * 본문 100만 자짜리 변경 이벤트는 약 3MB 다. 잘 줄지 않는 글로 보내야 broker 쪽 한도까지 확인된다 —
     * 같은 글자의 반복은 압축하면 몇 KB 가 되어, topic 의 한도가 기본값(약 1MB)이어도 통과해 버린다.
     */
    @Test
    void carriesAChangeEventAsLargeAsAMillionCharacterBody() throws Exception {
        String key = UUID.randomUUID().toString();
        // seed 가 같아 실행마다 같은 내용이다.
        String body = new Random(17).ints(3_000_000, 'a', 'z' + 1)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();

        kafkaTemplate.send(PROJECT_CHANGED, key, body).get(30, TimeUnit.SECONDS);

        assertThat(awaitRecords(PROJECT_CHANGED, key, 1).getFirst().value()).isEqualTo(body);
    }

    /**
     * 한도는 압축하기 전 크기로 잰다. 그래서 압축하면 아주 작아질 내용이라도 한도를 넘으면 보내기 전에 거절된다.
     */
    @Test
    void refusesARecordOverTheLimitBeforeItLeaves() {
        String key = UUID.randomUUID().toString();
        String body = "a".repeat(6 * 1024 * 1024 + 1);

        assertThatThrownBy(() -> kafkaTemplate.send(PROJECT_CHANGED, key, body))
                .hasRootCauseInstanceOf(RecordTooLargeException.class);
    }
}
