package com.loresentry.content.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * relay 가 지표로 내보내는 밀림({@link OutboxRepository#backlog()})이 relay 가 보낼 행과 같은 행만 세는지 확인한다. 보낸 행과
 * 격리된 행은 세지 않고, 가장 오래된 생성 시각은 보낼 행 중에서 고른다.
 *
 * <p>Kafka 는 필요 없어서 DB 만 띄우는 {@link ApiTestSupport} 를 쓴다. 행은 SQL 로 직접 넣는다.
 */
class OutboxBacklogTest extends ApiTestSupport {

    /** 테스트 대상. Spring 이 만든 것이라 실제 DB 를 읽는다. */
    @Autowired
    OutboxRepository repository;

    /**
     * 물려받은 정리는 projects 만 지운다. outbox 는 프로젝트와 외래 키로 묶여 있지 않아서, 다른 테스트가 남긴 행이
     * 그대로 있다. 매 테스트 전에 따로 비운다.
     */
    @BeforeEach
    void clearOutbox() {
        jdbcClient.sql("delete from outbox_events").update();
    }

    /**
     * outbox 에 행 하나를 넣는다. 세는 데 쓰는 것은 시각뿐이라 나머지 값은 아무것이나 넣는다.
     *
     * @param createdAt 행이 만들어진 시각
     * @param publishedAt 발행된 시각. 아직 보내지 않은 행이면 null
     * @param quarantinedAt 격리된 시각. 격리되지 않은 행이면 null
     */
    private void insert(OffsetDateTime createdAt, OffsetDateTime publishedAt, OffsetDateTime quarantinedAt) {
        // null 을 넘길 수 있는 칸에는 ::timestamptz 를 붙인다. 값이 null 이면 타입을 알 수 없어서 DB 에 알려 준다.
        jdbcClient
                .sql("""
                        insert into outbox_events
                            (event_type, event_key, aggregate_id, payload, created_at, published_at, quarantined_at)
                        values
                            ('FileChanged', :key, :aggregate, '{}'::jsonb, :created,
                             :published::timestamptz, :quarantined::timestamptz)
                        """)
                .param("key", UUID.randomUUID().toString())
                .param("aggregate", UUID.randomUUID())
                .param("created", createdAt)
                .param("published", publishedAt)
                .param("quarantined", quarantinedAt)
                .update();
    }

    /** 보낼 행이 없으면 0건이고 가장 오래된 시각은 없다. 보낸 행만 있어도 같다. */
    @Test
    void countsNothingWhenEverythingWasSent() {
        assertThat(repository.backlog()).isEqualTo(Backlog.NONE);

        OffsetDateTime now = OffsetDateTime.now();
        insert(now.minusHours(1), now.minusMinutes(59), null);

        assertThat(repository.backlog()).isEqualTo(Backlog.NONE);
    }

    /** 보내지 않은 행만 세고, 가장 오래된 시각도 그중에서 고른다. 보낸 행과 격리된 행이 더 오래됐어도 고르지 않는다. */
    @Test
    void countsOnlyRowsTheRelayWouldSend() {
        // PostgreSQL 의 timestamptz 는 마이크로초까지 담는다. 넣은 값과 읽은 값을 비교하려고 그 아래를 버린다.
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        // 보냈다.
        insert(now.minusHours(3), now.minusHours(2), null);
        // 격리됐다.
        insert(now.minusHours(2), null, now.minusHours(1));
        // 아직 보내지 않았다. 둘 중 앞의 것이 가장 오래됐다.
        insert(now.minusMinutes(10), null, null);
        insert(now.minusMinutes(5), null, null);

        Backlog backlog = repository.backlog();

        assertThat(backlog.pending()).isEqualTo(2);
        assertThat(backlog.oldest().toInstant()).isEqualTo(now.minusMinutes(10).toInstant());
    }
}
