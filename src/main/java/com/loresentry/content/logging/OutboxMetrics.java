package com.loresentry.content.logging;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.Meter;

/**
 * outbox 밀림 지표(docs ALERTING.md 7절 "앱 지표", 경보 OutboxBacklog). relay가 알려 준 값을 담아 두었다가, agent가 지표를
 * 내보낼 때(1분마다) observable gauge 세 개가 읽는다.
 *
 * <ul>
 *   <li>{@value #PENDING}: 아직 보내지 않았고 격리되지도 않은 행 수</li>
 *   <li>{@value #OLDEST_AGE}: 그중 가장 오래된 행이 들어온 뒤 지난 초. 보낼 행이 없으면 0이다. Kafka가 끊겨 relay가 쉬는 동안에도
 *       내보내는 순간의 시각으로 다시 재므로 계속 커진다</li>
 *   <li>{@value #LAST_RUN_AGE}: relay가 마지막으로 불린 뒤 지난 초. 스케줄러가 멈추면 계속 커진다</li>
 * </ul>
 *
 * <p>지표 API(OpenTelemetry)는 로깅 팩토리 패키지만 쓴다(LoggingArchitectureTest). relay는 이 객체에 값만 넘긴다. 앱이 막 떠서
 * 아직 값을 모르면 그 지표는 내보내지 않는다(0으로 내보내면 "밀린 것 없음"으로 읽힌다).
 *
 * <p>지표 전송이 꺼진 환경(prod 기본)에서는 agent의 meter가 아무것도 하지 않아 비용이 없다.
 */
public final class OutboxMetrics {
    /** 보내지 않은 행 수 지표 이름. */
    static final String PENDING = "loresentry.outbox.pending";

    /** 가장 오래된 보내지 않은 행의 나이(초) 지표 이름. */
    static final String OLDEST_AGE = "loresentry.outbox.oldest_age";

    /** relay가 마지막으로 불린 뒤 지난 초 지표 이름. */
    static final String LAST_RUN_AGE = "loresentry.outbox.relay.last_run_age";

    /** relay가 잰 밀림. 보낼 행이 없으면 oldest가 null이다. */
    private record Backlog(long pending, Instant oldest) {
    }

    /** 나이를 잴 때 쓰는 시계. relay와 같은 시계를 받아 테스트가 시간을 앞당길 수 있다. */
    private final InstantSource clock;

    /** 마지막으로 잰 밀림. relay(스케줄러 스레드)가 쓰고 지표를 내보내는 스레드가 읽으므로 {@code volatile}이다. 아직 재지 않았으면 null이다. */
    private volatile Backlog backlog;

    /** relay가 마지막으로 불린 시각. {@link #backlog}와 같은 이유로 {@code volatile}이다. 아직 불리지 않았으면 null이다. */
    private volatile Instant lastRun;

    /**
     * 운영에서 쓰는 객체를 만든다. 실행 중에는 agent의 meter에 gauge가 등록된다. 객체 하나가 gauge 세 개를 등록하므로 relay 하나에
     * 한 번만 부른다.
     *
     * @param clock 나이를 잴 시계
     * @return gauge를 등록한 객체
     */
    public static OutboxMetrics register(InstantSource clock) {
        return new OutboxMetrics(GlobalOpenTelemetry.getMeter(TraceContext.INSTRUMENTATION_SCOPE), clock);
    }

    /**
     * gauge 세 개를 등록한다. 테스트는 시험용 SDK의 meter를 넘긴다.
     *
     * @param meter gauge를 등록할 meter
     * @param clock 나이를 잴 시계
     */
    OutboxMetrics(Meter meter, InstantSource clock) {
        this.clock = clock;
        meter.gaugeBuilder(PENDING).ofLongs().setUnit("{row}").setDescription("Outbox rows not yet published or quarantined")
                .buildWithCallback(gauge -> {
                    Backlog measured = backlog;
                    if (measured != null) gauge.record(measured.pending());
                });
        meter.gaugeBuilder(OLDEST_AGE).setUnit("s").setDescription("Age of the oldest outbox row not yet published")
                .buildWithCallback(gauge -> {
                    Backlog measured = backlog;
                    if (measured == null) return;
                    gauge.record(measured.oldest() == null ? 0 : secondsSince(measured.oldest()));
                });
        meter.gaugeBuilder(LAST_RUN_AGE).setUnit("s").setDescription("Seconds since the outbox relay last ran")
                .buildWithCallback(gauge -> {
                    Instant ran = lastRun;
                    if (ran != null) gauge.record(secondsSince(ran));
                });
    }

    /** relay가 불렸다. 쉬는 중이라 회차를 돌지 않았어도 부른다(스케줄러가 살아 있다는 뜻이다). */
    public void relayRan() {
        lastRun = clock.instant();
    }

    /**
     * relay가 잰 밀림을 알린다.
     *
     * @param pending 보내지 않았고 격리되지도 않은 행 수
     * @param oldest  그중 가장 오래된 행의 생성 시각. 행이 없으면 null
     */
    public void backlog(long pending, Instant oldest) {
        backlog = new Backlog(pending, pending == 0 ? null : oldest);
    }

    /** 그 시각부터 지금까지 지난 초. 시계가 거꾸로 가도(DB 서버의 시각) 음수를 내지 않는다. */
    private double secondsSince(Instant since) {
        Duration elapsed = Duration.between(since, clock.instant());
        return elapsed.isNegative() ? 0 : elapsed.toMillis() / 1000.0;
    }
}
