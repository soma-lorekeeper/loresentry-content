package com.loresentry.content.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.loresentry.content.support.ApiTestSupport;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaUtils;

/**
 * 앱이 뜬 뒤에는 spring-kafka 가 보내는 record 를 로그에 옮길 때 내용(value)을 빼는지 확인한다. 이 형식은 앱 전체에
 * 한 번 정해지는 값이라, 앱을 띄우는 {@link ApiTestSupport} 위에서 본다.
 */
class KafkaConfigurationTest extends ApiTestSupport {

    @Test
    void leavesTheValueOutWhenARecordBeingSentIsLogged() {
        String formatted = KafkaUtils.format(new ProducerRecord<>("content.project.changed.v1", "key", "원고 본문"));

        assertThat(formatted).contains("content.project.changed.v1").doesNotContain("원고 본문");
    }
}
