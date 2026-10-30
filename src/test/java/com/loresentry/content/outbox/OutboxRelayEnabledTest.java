package com.loresentry.content.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.loresentry.content.support.KafkaTestSupport;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * relay 를 켠 앱에서, outbox 에 들어간 행이 누가 부르지 않아도 Kafka 로 나가는지 확인한다. 다른 테스트는 한 회차를
 * 직접 부르지만 여기서는 부르지 않는다 — 스케줄러, 켜고 끄는 설정, publisher 가 실제로 이어져 있는지를 본다.
 *
 * <p>{@code @TestPropertySource} 는 이 테스트에서만 relay 를 켠다. 설정이 달라지면 Spring 이 앱을 따로 하나 더
 * 띄운다.
 *
 * <p>{@code @DirtiesContext} 는 이 테스트가 끝나면 그 앱을 닫게 한다. Spring 은 보통 띄운 앱을 다음 테스트에 다시
 * 쓰려고 살려 두는데, 그러면 켜진 relay 가 뒤에서 계속 돌아 다른 테스트가 넣은 행을 가져가 버린다.
 */
@TestPropertySource(properties = "kafka.producer.relay.enabled=true")
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class OutboxRelayEnabledTest extends KafkaTestSupport {

    /** FileChanged 가 실려야 하는 topic. main 의 상수와 따로 적어, 그 값이 틀리면 테스트가 알아채게 한다. */
    private static final String PROJECT_CHANGED = "content.project.changed.v2";

    @Test
    void sendsAPendingRowWithoutBeingAsked(CapturedOutput output) {
        String projectId = UUID.randomUUID().toString();
        UUID fileId = UUID.randomUUID();
        // outbox 에 쓰는 쪽이 할 일을 대신한다. 넣기만 하고 publishBatch 는 부르지 않는다.
        UUID id = jdbcClient
                .sql("""
                        insert into outbox_events (event_type, event_key, aggregate_id, payload)
                        values ('FileChanged', :key, :aggregate, :payload::jsonb)
                        returning id
                        """)
                .param("key", projectId)
                .param("aggregate", fileId)
                .param("payload", "{\"file_id\":\"" + fileId + "\"}")
                .query(UUID.class)
                .single();

        // relay 가 1초 안에 깨어나 이 행을 보낸다. 레코드의 ce_id 가 넣은 행의 id 다.
        ConsumerRecord<String, String> record = awaitRecords(PROJECT_CHANGED, projectId, 1).getFirst();
        assertThat(new String(record.headers().lastHeader("ce_id").value(), StandardCharsets.UTF_8))
                .isEqualTo(id.toString());

        // 레코드가 Kafka 에 보인 직후에는 발행 표시의 commit 이 아직일 수 있다. 잠시 기다리며 확인한다.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            OffsetDateTime publishedAt = jdbcClient.sql("select published_at from outbox_events where id = :id")
                    .param("id", id)
                    .query((rows, index) -> rows.getObject("published_at", OffsetDateTime.class))
                    .optional()
                    .orElse(null);
            assertThat(publishedAt).isNotNull();
            // 회차가 끝나면 relay 가 요약을 한 줄 남긴다. 운영에서 relay 가 돌고 있는지를 이 줄로 본다.
            assertThat(output.getOut()).contains("outbox relay: published=1, quarantined=0, failed=0");
        });
    }
}
