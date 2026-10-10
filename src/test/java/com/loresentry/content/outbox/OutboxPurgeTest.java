package com.loresentry.content.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.support.CronExpression;

/**
 * 정리 작업이 지워야 할 행만 지우는지, 그리고 돌 때마다 흔적을 남기는지 확인한다. 스케줄러가 부르기를 기다리지 않고
 * 테스트가 {@code run()} 을 직접 부른다.
 *
 * <p>Kafka 는 필요 없어서 DB 만 띄우는 {@link ApiTestSupport} 를 쓴다. 행은 SQL 로 직접 넣고, 발행 시각과 격리
 * 시각을 지금 기준으로 정한다. 경계(3일)에서 한 시간씩 떨어뜨려 두어, 테스트가 도는 몇 밀리초로는 결과가 바뀌지
 * 않는다.
 *
 * <p>{@link OutputCaptureExtension} 은 테스트가 도는 동안 찍힌 로그를 모아 준다. 정리 작업의 로그 문구는 운영에서
 * "오늘 돌았는가"를 보는 기준이므로, 문구가 바뀌면 테스트가 알아채게 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class OutboxPurgeTest extends ApiTestSupport {

    /** 테스트 대상. Spring 이 만든 것이라 실제 DB 를 지운다. */
    @Autowired
    OutboxPurge purge;

    /**
     * 물려받은 정리는 projects 만 지운다. outbox 는 프로젝트와 외래 키로 묶여 있지 않아서, 다른 테스트가 남긴 행이
     * 그대로 있다. 매 테스트 전에 따로 비운다.
     */
    @BeforeEach
    void clearOutbox() {
        jdbcClient.sql("delete from outbox_events").update();
    }

    /**
     * outbox 에 행 하나를 넣는다. 정리 작업이 보는 것은 시각뿐이라 나머지 값은 아무것이나 넣는다.
     *
     * @param createdAt 행이 만들어진 시각
     * @param publishedAt 발행된 시각. 아직 보내지 않은 행이면 null
     * @param quarantinedAt 격리된 시각. 격리되지 않은 행이면 null
     * @return DB 가 만든 행의 id
     */
    private UUID insert(OffsetDateTime createdAt, OffsetDateTime publishedAt, OffsetDateTime quarantinedAt) {
        // null 을 넘길 수 있는 칸에는 ::timestamptz 를 붙인다. 값이 null 이면 타입을 알 수 없어서 DB 에 알려 준다.
        return jdbcClient
                .sql("""
                        insert into outbox_events
                            (event_type, event_key, aggregate_id, payload, created_at, published_at, quarantined_at)
                        values
                            ('FileChanged', :key, :aggregate, '{}'::jsonb, :created,
                             :published::timestamptz, :quarantined::timestamptz)
                        returning id
                        """)
                .param("key", UUID.randomUUID().toString())
                .param("aggregate", UUID.randomUUID())
                .param("created", createdAt)
                .param("published", publishedAt)
                .param("quarantined", quarantinedAt)
                .query(UUID.class)
                .single();
    }

    /** 지금 outbox 에 남아 있는 행의 id. */
    private List<UUID> remaining() {
        return jdbcClient.sql("select id from outbox_events").query(UUID.class).list();
    }

    /**
     * 지워지는 것은 발행한 지 3일이 지난 행뿐이다. 오래됐더라도 아직 보내지 않았거나 격리된 행은 남는다 — 앞쪽은
     * 보내야 할 이벤트이고, 뒤쪽은 사람이 봐야 할 이벤트다.
     */
    @Test
    void deletesOnlyRowsPublishedMoreThanThreeDaysAgo(CapturedOutput output) {
        OffsetDateTime now = OffsetDateTime.now();
        // 3일하고 1시간 전에 발행됐다. 보존 기간이 지났으므로 지워진다.
        UUID expired = insert(now.minusDays(4), now.minusDays(3).minusHours(1), null);
        // 3일에서 1시간 모자란다. 아직 보존 기간 안이다.
        UUID stillKept = insert(now.minusDays(4), now.minusDays(3).plusHours(1), null);
        // 열흘 전에 만들어졌지만 아직 보내지 못했다.
        UUID pending = insert(now.minusDays(10), null, null);
        // 열흘 전에 격리됐다.
        UUID quarantined = insert(now.minusDays(10), null, now.minusDays(10));

        purge.run();

        assertThat(remaining()).containsExactlyInAnyOrder(stillKept, pending, quarantined);
        assertThat(remaining()).doesNotContain(expired);
        // 운영에서 이 문구로 정리 작업의 결과를 본다.
        assertThat(output.getOut()).contains("outbox purge: deleted=1, took=");
    }

    /**
     * 지울 것이 없어도 한 줄을 남긴다. 하루 한 번 도는 작업이라, 줄이 없으면 "지울 것이 없었다"와 "돌지 않았다"를
     * 구분할 수 없다.
     */
    @Test
    void leavesALineEvenWhenThereWasNothingToDelete(CapturedOutput output) {
        purge.run();

        assertThat(output.getOut()).contains("outbox purge: deleted=0, took=");
    }

    /**
     * 삭제가 실패해도(예: DB 에 닿지 못함) 예외를 밖으로 내보내지 않고, 예외의 종류만 한 줄 남긴다. 실패를 만들기 위해
     * 무엇을 부르든 예외를 던지는 가짜 repository 를 끼운다.
     */
    @Test
    void leavesTheKindOfFailureInsteadOfThrowing(CapturedOutput output) {
        OutboxRepository broken = mock(OutboxRepository.class);
        when(broken.deletePublishedBefore(any())).thenThrow(new IllegalStateException("database is down"));

        // 예외가 여기까지 올라오지 않는다.
        new OutboxPurge(broken).run();

        assertThat(output.getOut()).contains("outbox purge failed: reason=java.lang.IllegalStateException");
        // 예외의 메시지는 로그에 적지 않는다.
        assertThat(output.getOut()).doesNotContain("database is down");
    }

    /**
     * cron 표현식과 시간대가 "매일 한국 시각 04:00" 을 뜻하는지 확인한다. 여섯 칸의 순서를 바꿔 적거나 시간대를
     * 빠뜨리는 실수는 앱이 멀쩡히 뜬 채로 엉뚱한 시각에 돌게 만든다.
     */
    @Test
    void runsOnceADayAtFourInTheMorningKoreaTime() {
        ZoneId zone = ZoneId.of(OutboxPurge.ZONE);
        CronExpression daily = CronExpression.parse(OutboxPurge.DAILY_AT_FOUR);
        ZonedDateTime midnight = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone);

        // 자정 뒤의 첫 실행은 그날 04:00 이다.
        ZonedDateTime first = daily.next(midnight);
        assertThat(first).isEqualTo(ZonedDateTime.of(2026, 1, 1, 4, 0, 0, 0, zone));
        // 그다음 실행은 정확히 하루 뒤다.
        assertThat(daily.next(first)).isEqualTo(first.plusDays(1));
        // 서버가 쓰는 UTC 로는 전날 19:00 이다.
        assertThat(first.withZoneSameInstant(ZoneOffset.UTC))
                .isEqualTo(ZonedDateTime.of(2025, 12, 31, 19, 0, 0, 0, ZoneOffset.UTC));
    }
}
