package com.loresentry.content.inbox;

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
 * 정리 작업이 지워야 할 처리 기록만 지우는지, 그리고 돌 때마다 흔적을 남기는지 확인한다. 스케줄러가 부르기를 기다리지
 * 않고 테스트가 {@code run()} 을 직접 부른다. outbox 의 정리 작업 테스트와 같은 모양이다.
 *
 * <p>Kafka 는 필요 없어서 DB 만 띄우는 {@link ApiTestSupport} 를 쓴다. 기록은 SQL 로 직접 넣고, 처리 시각을 지금
 * 기준으로 정한다. 경계(40일)에서 한 시간씩 떨어뜨려 두어, 테스트가 도는 몇 밀리초로는 결과가 바뀌지 않는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class InboxPurgeTest extends ApiTestSupport {

    /** 테스트 대상. Spring 이 만든 것이라 실제 DB 를 지운다. */
    @Autowired
    InboxPurge purge;

    /**
     * 물려받은 정리는 projects 만 지운다. inbox 는 프로젝트와 외래 키로 묶여 있지 않아서, 다른 테스트가 남긴 기록이
     * 그대로 있다. 매 테스트 전에 따로 비운다.
     */
    @BeforeEach
    void clearInbox() {
        jdbcClient.sql("delete from inbox_events").update();
    }

    /**
     * 처리 기록 하나를 넣는다. 정리 작업이 보는 것은 처리 시각뿐이라 처리기 이름은 아무것이나 넣는다.
     *
     * @param processedAt 처리된 시각
     * @return 넣은 기록의 이벤트 id
     */
    private UUID insert(OffsetDateTime processedAt) {
        UUID eventId = UUID.randomUUID();
        jdbcClient.sql("""
                        insert into inbox_events (consumer_name, event_id, processed_at)
                        values ('test.inbox-purge', :event, :processed)
                        """)
                .param("event", eventId)
                .param("processed", processedAt)
                .update();
        return eventId;
    }

    /** 지금 inbox 에 남아 있는 기록의 이벤트 id. */
    private List<UUID> remaining() {
        return jdbcClient.sql("select event_id from inbox_events").query(UUID.class).list();
    }

    /** 지워지는 것은 처리한 지 40일이 지난 기록뿐이다. */
    @Test
    void deletesOnlyRecordsOlderThanFortyDays(CapturedOutput output) {
        OffsetDateTime now = OffsetDateTime.now();
        // 40일하고 1시간 전에 처리됐다. 보존 기간이 지났으므로 지워진다.
        UUID expired = insert(now.minusDays(40).minusHours(1));
        // 40일에서 1시간 모자란다. 아직 보존 기간 안이다 — DLQ 에서 재투입될 수 있는 기간이다.
        UUID stillKept = insert(now.minusDays(40).plusHours(1));
        // 방금 처리됐다.
        UUID recent = insert(now);

        purge.run();

        assertThat(remaining()).containsExactlyInAnyOrder(stillKept, recent);
        assertThat(remaining()).doesNotContain(expired);
        // 운영에서 이 문구로 정리 작업의 결과를 본다.
        assertThat(output.getOut()).contains("inbox purge: deleted=1, took=");
    }

    /**
     * 지울 것이 없어도 한 줄을 남긴다. 하루 한 번 도는 작업이라, 줄이 없으면 "지울 것이 없었다"와 "돌지 않았다"를
     * 구분할 수 없다.
     */
    @Test
    void leavesALineEvenWhenThereWasNothingToDelete(CapturedOutput output) {
        purge.run();

        assertThat(output.getOut()).contains("inbox purge: deleted=0, took=");
    }

    /**
     * 삭제가 실패해도(예: DB 에 닿지 못함) 예외를 밖으로 내보내지 않고, 예외의 종류만 한 줄 남긴다. 실패를 만들기 위해
     * 무엇을 부르든 예외를 던지는 가짜 repository 를 끼운다.
     */
    @Test
    void leavesTheKindOfFailureInsteadOfThrowing(CapturedOutput output) {
        InboxRepository broken = mock(InboxRepository.class);
        when(broken.deleteProcessedBefore(any())).thenThrow(new IllegalStateException("database is down"));

        // 예외가 여기까지 올라오지 않는다.
        new InboxPurge(broken).run();

        assertThat(output.getOut()).contains("inbox purge failed: reason=java.lang.IllegalStateException");
        // 예외의 메시지는 로그에 적지 않는다.
        assertThat(output.getOut()).doesNotContain("database is down");
    }

    /**
     * cron 표현식과 시간대가 "매일 한국 시각 04:00" 을 뜻하는지 확인한다. 여섯 칸의 순서를 바꿔 적거나 시간대를
     * 빠뜨리는 실수는 앱이 멀쩡히 뜬 채로 엉뚱한 시각에 돌게 만든다.
     */
    @Test
    void runsOnceADayAtFourInTheMorningKoreaTime() {
        ZoneId zone = ZoneId.of(InboxPurge.ZONE);
        CronExpression daily = CronExpression.parse(InboxPurge.DAILY_AT_FOUR);
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
