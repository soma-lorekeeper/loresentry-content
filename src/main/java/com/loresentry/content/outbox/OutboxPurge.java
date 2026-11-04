package com.loresentry.content.outbox;

import java.time.Duration;
import java.time.OffsetDateTime;

import com.loresentry.content.logging.LogField;
import com.loresentry.content.logging.LogFlow;
import com.loresentry.content.logging.LogLayer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 발행이 끝난 지 오래된 outbox 행을 하루 한 번 지운다. relay 는 보낸 행에 발행 표시만 하고 남겨 두므로, 이 작업이
 * 없으면 테이블이 끝없이 커진다.
 *
 * <p>동작은 단순하다. 매일 정해진 시각에 깨어나, 지금에서 {@link #RETENTION} 을 뺀 시각보다 먼저 발행된 행을
 * 한 번에 지우고, 지운 개수를 한 줄 남긴다.
 *
 * <p>relay({@link OutboxRelay})와 서로 방해하지 않는다. relay 는 아직 보내지 않은 행만 건드리고 이 작업은 발행된
 * 지 오래된 행만 건드린다. 스케줄러에서도 cron 작업은 별도의 thread 에서 돌아, 1초마다 도는 relay 를 기다리게
 * 하지 않는다.
 *
 * <p>현재 시각은 직접 읽는다. 시간 계산이 뺄셈 한 번뿐이라, 테스트는 행의 시각을 지금 기준으로 잡으면 된다.
 */
@Component
@LogLayer("scheduler")
class OutboxPurge {


    /**
     * 발행된 행을 남겨 두는 기간. Kafka topic 의 보존 기간(3일)과 맞췄다 — 그 뒤에는 Kafka 에서도 메시지가 사라져,
     * outbox 의 행을 다시 볼 일이 없다. 하루 한 번 지우므로 실제로는 3일에서 4일 사이 남는다.
     */
    /** 로그의 작업 이름(loresentry.schedule.name). */
    static final String SCHEDULE = "outbox-purge";

    static final Duration RETENTION = Duration.ofDays(3);

    /**
     * 도는 시각. 여섯 칸은 차례로 초, 분, 시, 일, 월, 요일이다. "0초 0분 4시, 날짜와 요일은 아무 때나", 곧 매일
     * 04:00:00 이다. 사용이 가장 적은 시간대로 잡았다.
     */
    static final String DAILY_AT_FOUR = "0 0 4 * * *";

    /**
     * 위 시각을 읽는 시간대. 적지 않으면 서버의 시간대를 따르는데, container 는 UTC 로 돌아서 "04:00" 이 한국 시각
     * 13시가 된다.
     */
    static final String ZONE = "Asia/Seoul";

    /** outbox_events 를 읽고 쓰는 창구. 여기서는 삭제 하나만 쓴다. */
    private final OutboxRepository repository;

    /** 생성자가 하나뿐이라 Spring 이 OutboxRepository 를 찾아 넣어 준다. */
    OutboxPurge(OutboxRepository repository) {
        this.repository = repository;
    }

    /**
     * 스케줄러가 매일 한 번 부른다. 지우고, 시작·종료 줄을 남긴다.
     *
     * <p>지운 것이 없어도 남긴다. 하루 한 번 도는 작업이라, 줄이 없으면 "지울 것이 없었다"와 "돌지 않았다"를 가릴
     * 수 없기 때문이다. 그날 이 줄이 하나도 없으면 돌지 않은 것이다.
     *
     * <p>예외를 밖으로 내보내지 않는다. 내보내면 스케줄러가 예외 메시지와 SQL 이 든 오류를 찍는데, 이 저장소는
     * 그것을 로그에 남기지 않는다. LogFlow 가 받아서 예외의 종류와 메시지 없는 스택만 남긴다. 실패한 날의 행은 다음 날 함께 지워진다.
     */
    @Scheduled(cron = DAILY_AT_FOUR, zone = ZONE)
    void run() {
        // 회차 하나의 흐름. 로그 객체·시작 줄(보존 기간)·종료 줄(지운 수, 걸린 시간)·실패 줄은 LogFlow 가 남긴다(LOG_SCHEMA.md 5절).
        // 실패해도 예외를 밖으로 내보내지 않는다. 트랜잭션이 rollback 되어 지워진 것이 없고, 남은 것은 다음 날 함께 지워진다.
        LogFlow.schedule(SCHEDULE, run -> {
            // 지금에서 보존 기간을 뺀 시각. 이보다 먼저 발행된 행이 지워진다.
            OffsetDateTime cutoff = OffsetDateTime.now().minus(RETENTION);
            // 지운 수는 종료 줄에 남는다. 커지는 추세가 보이면 한 번에 지우는 양에 상한을 둘 때다
            run.result(LogField.result("deleted", repository.deletePublishedBefore(cutoff)));
        }, LogField.param("retention_days", RETENTION.toDays()));
    }
}
