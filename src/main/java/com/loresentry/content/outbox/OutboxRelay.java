package com.loresentry.content.outbox;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.function.Supplier;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.logging.LogFlow;
import com.loresentry.content.logging.LogLayer;
import com.loresentry.content.logging.OutboxMetrics;
import com.loresentry.content.logging.SkipEntryLog;
import com.loresentry.content.outbox.Round.Outcome;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * relay 를 주기적으로 돌린다. 한 회차가 무엇을 하는지는 {@link OutboxPublisher#publishBatch()} 가 알고, 이 클래스는
 * 언제 부르고 언제 쉴지만 정한다.
 *
 * <p>Spring 의 스케줄러가 {@link #run()} 을 {@link #PERIOD_MILLIS} 마다 부른다. 한 번 불리면 다음과 같이 움직인다.
 *
 * <ol>
 *   <li>쉬는 중이면 아무것도 하지 않고 돌아간다.
 *   <li>회차가 "더 있다"고 하는 동안 곧바로 다음 회차를 돈다. 밀린 행을 주기를 기다리지 않고 비운다.
 *   <li>회차가 실패하면 쉬는 시간을 늘린다(backoff). 실패가 아니면 평소 상태로 돌아간다.
 *   <li>보내거나 격리하거나 실패한 행이 있었으면 요약을 한 줄 남긴다.
 * </ol>
 *
 * <p>불릴 때마다 지표(OutboxMetrics)에 "relay 가 돌았다"를 알리고, {@link #BACKLOG_EVERY} 마다 밀린 행을 세어 함께 알린다(docs
 * ALERTING.md 7절, 경보 OutboxBacklog). 세는 일은 줄을 남기지 않으므로 할 일이 없는 회차는 여전히 아무 줄도 남기지 않는다.
 *
 * <p>{@code kafka.producer.relay.enabled} 가 false 면 이 객체가 만들어지지 않아 relay 가 돌지 않는다. 값을 적지 않으면
 * 켠 것으로 본다. 테스트에서는 꺼 둔다 — 뒤에서 돌면 테스트가 넣은 행을 먼저 가져가 버린다.
 *
 * <p>{@link OutboxPublisher} 와 클래스를 나눈 데에는 이유가 있다. {@code publishBatch} 의 트랜잭션은 다른 객체가
 * 불러야 열린다. 같은 클래스 안에서 부르면 열리지 않는다.
 */
@Component
@ConditionalOnProperty(name = "kafka.producer.relay.enabled", havingValue = "true", matchIfMissing = true)
@LogLayer("scheduler")
class OutboxRelay {

    /**
     * 평소 주기(밀리초). 앞 호출이 끝난 뒤 이만큼 지나면 다시 불린다. 이벤트가 outbox 에 들어간 뒤 Kafka 로
     * 나가기까지의 최대 대기 시간이기도 하다. 처리량은 이 값이 아니라 "더 있는 동안 이어서 도는 것"이 정한다.
     */
    static final long PERIOD_MILLIS = 1_000;

    /** 로그의 작업 이름(loresentry.schedule.name). 행마다의 발행 줄({@link OutboxPublisher})에도 붙인다. */
    static final String SCHEDULE = "outbox-relay";
    /** 다시 보내야 할 행이 남아 쉬는 시간을 늘린 회차의 error.type. 종료 줄(schedule run failed)에 붙는다. */
    static final String RELAY_FAILED = "OUTBOX_SEND_FAILED";

    /** 처음 실패했을 때 쉬는 시간. 평소 주기와 같아서, 한 번의 실패는 평소와 다르지 않게 넘어간다. */
    static final Duration MIN_BACKOFF = Duration.ofSeconds(1);

    /**
     * 쉬는 시간의 상한. broker 가 돌아온 뒤 그것을 알아채기까지의 최대 지연이다. 더 길게 잡아도 아끼는 것이 거의
     * 없다 — broker 로의 재접속은 Kafka producer 가 이 값과 무관하게 약 1초마다 시도하고 있다.
     */
    static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    /** 밀린 행을 세는 간격. 지표는 1분마다 나가므로 그보다 짧으면 충분하다. 1초 주기마다 세면 DB 에 하루 8만 번 넘게 묻게 된다. */
    static final Duration BACKLOG_EVERY = Duration.ofSeconds(15);

    /** 회차를 한 번 돌리고 그 결과를 돌려주는 함수. 운영에서는 {@link OutboxPublisher#publishBatch()} 다. */
    private final Supplier<Round> round;

    /** 밀린 행을 세는 함수. 운영에서는 {@link OutboxRepository#backlog()} 다. */
    private final Supplier<Backlog> backlog;

    /** outbox 밀림 지표. 불릴 때마다, 그리고 셀 때마다 값을 넘긴다. */
    private final OutboxMetrics metrics;

    /** 지금 시각을 알려 주는 시계. 테스트가 시간을 손으로 앞당길 수 있게 건네받는다. */
    private final InstantSource clock;

    /** 이 시각이 지나면 다음 호출에서 회차를 다 돈 뒤 밀린 행을 센다. 처음에는 곧바로 센다. */
    private Instant nextBacklogAt = Instant.MIN;

    /** 지금 적용 중인 쉬는 시간. 0 이면 평소 상태다. 실패가 이어지면 {@link #nextBackoff(Duration)} 로 늘어난다. */
    private Duration backoff = Duration.ZERO;

    /** 이 시각 전에는 불려도 회차를 돌지 않는다. 실패한 회차가 끝난 시각에 쉬는 시간을 더한 값이다. */
    private Instant resumeAt = Instant.MIN;

    /**
     * 앱이 종료를 시작했는가. 종료를 진행하는 thread 가 켜고 스케줄러의 thread 가 읽으므로 {@code volatile} 이다.
     * 다른 세 필드는 스케줄러가 호출을 겹치지 않게 차례로 부르므로 그런 보호가 필요 없다.
     */
    private volatile boolean stopping;

    /**
     * 운영에서 Spring 이 쓰는 생성자. 생성자가 둘이라 어느 쪽을 쓸지 {@code @Autowired} 로 알려 준다.
     *
     * <p>{@code publisher} 는 Spring 이 감싼 proxy 다. 그 메서드를 함수로 넘기면 호출이 proxy 를 거치므로
     * 회차마다 트랜잭션이 열린다.
     *
     * <p>지표 객체는 relay 하나에 하나만 만든다. 만들 때 agent 의 meter 에 gauge 를 등록한다.
     */
    @Autowired
    OutboxRelay(OutboxPublisher publisher, OutboxRepository repository) {
        this(publisher::publishBatch, repository::backlog, OutboxMetrics.register(InstantSource.system()),
                InstantSource.system());
    }

    /**
     * 테스트가 쓰는 생성자. 회차와 시계를 바꿔 끼울 수 있어서 DB 와 Kafka 없이, 실제로 기다리지 않고 이 클래스의
     * 판단만 확인할 수 있다. 밀린 행은 늘 없다고 센다.
     *
     * @param round 회차를 한 번 돌리는 함수
     * @param clock 지금 시각을 알려 주는 시계
     */
    OutboxRelay(Supplier<Round> round, InstantSource clock) {
        this(round, () -> Backlog.NONE, OutboxMetrics.register(clock), clock);
    }

    /**
     * 지표까지 바꿔 끼우는 생성자. 운영 생성자와 위 테스트 생성자가 모두 이것을 부른다.
     *
     * @param round 회차를 한 번 돌리는 함수
     * @param backlog 밀린 행을 세는 함수
     * @param metrics 센 값을 받을 지표
     * @param clock 지금 시각을 알려 주는 시계
     */
    OutboxRelay(Supplier<Round> round, Supplier<Backlog> backlog, OutboxMetrics metrics, InstantSource clock) {
        this.round = round;
        this.backlog = backlog;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * 스케줄러가 주기마다 부른다. 흐름은 클래스 설명의 네 단계이고, 그 앞뒤로 지표에 값을 넘긴다(돌았다는 표시, 밀린 행).
     *
     * <p>예외를 밖으로 내보내지 않는다. 내보내면 스케줄러가 긴 오류 로그를 주기마다 찍는다. 받아서 실패한 회차처럼
     * 쉬는 시간을 늘린다.
     *
     * <p>로그에는 개수와 예외의 종류만 적는다 — 행의 내용과 예외 메시지는 적지 않는다. 회차 하나의 trace는 EDOT agent 의
     * Spring Scheduling 계측이 열고, 보낸 행마다의 줄은 {@link OutboxPublisher} 가 그 행을 만든 요청의 trace 로 남긴다.
     */
    @Scheduled(fixedDelay = PERIOD_MILLIS)
    @SkipEntryLog("Polls every second. Logs start and end lines only in rounds that have rows to send")
    void run() {
        // 스케줄러가 살아 있다는 표시. 쉬는 중이라 회차를 돌지 않아도 남긴다. 이 값이 오래되면 스케줄러가 멈춘 것이다
        metrics.relayRan();
        // 회차 하나의 흐름(LogFlow.polling). 보낼 행이 있어 줄이 찍힌 회차에서만 시작·종료 줄(개수, 걸린 시간)이 남는다. 주기마다
        // 불리므로 빈 회차까지 남기면 하루 수만 줄이 "0건"으로 찬다. 종료 줄은 정상일 때의 흔적이기도 하다 — 이 줄이 없으면 "잘 보내는
        // 중"과 "relay 가 멈춤"을 로그로 가릴 수 없다. 예외는 LogFlow 가 받아 실패 줄로 남기고 밖으로 내보내지 않는다.
        LogFlow.polling(SCHEDULE, run -> {
            // 1. 아직 쉴 시간이면 곧바로 돌아간다. 쉬는 동안의 호출은 이 비교 한 번이 전부다.
            if (clock.instant().isBefore(resumeAt)) {
                return;
            }

            // 2. 회차가 "더 있다"고 하는 동안 이어서 돈다.
            // 이번 호출에서 보낸 행, 격리한 행, 다시 보내야 할 행의 합계와 돈 회차 수. 종료 줄의 결과 값이다.
            int published = 0;
            int quarantined = 0;
            int failed = 0;
            int rounds = 0;
            // 마지막 회차의 결과. MORE 로 시작해야 첫 회차를 돈다.
            Outcome outcome = Outcome.MORE;
            // 종료가 시작됐으면 다음 회차를 시작하지 않는다. 회차 도중에는 트랜잭션이 열려 있어 끊으면 rollback 되지만,
            // 회차와 회차 사이에는 열려 있는 것이 없다. Spring 은 이 메서드가 끝나기를 기다렸다가 종료를 이어 간다.
            while (outcome == Outcome.MORE && !stopping) {
                Round result;
                try {
                    result = round.get();
                } catch (RuntimeException failure) {
                    // 회차가 결과를 돌려주지 못하고 예외로 끝났다(예: DB 에 닿지 못함). 그 회차는 rollback 됐으므로
                    // 개수에 더할 것이 없다. 실패한 회차처럼 다뤄 쉬게 한다. 로그에는 예외의 종류와 메시지 없는 스택만 적는다.
                    AppLogger.current().error("outbox relay round failed", LogField.errorType(failure.getClass().getName()),
                            LogField.safeStackTrace(failure));
                    outcome = Outcome.FAILED;
                    break;
                }
                rounds++;
                published += result.published();
                quarantined += result.quarantined();
                failed += result.failed();
                outcome = result.outcome();
            }

            // 3. 실패로 끝났으면 쉬는 시간을 늘리고, 지금부터 그만큼 쉰다. 호출을 시작한 시각이 아니라 지금 시각에서
            // 잰다 — 실패하는 회차는 그 자체로 5초에서 20초가 걸려, 시작 시각에서 재면 쉬는 시간이 이미 지나 있다.
            if (outcome == Outcome.FAILED) {
                backoff = nextBackoff(backoff);
                resumeAt = clock.instant().plus(backoff);
                // 다시 보내야 할 행이 남았다. 종료 줄이 warn 이 되고 늘린 쉬는 시간을 함께 남긴다
                run.fail(RELAY_FAILED).result(LogField.result("backoff_seconds", backoff.toSeconds()));
            } else {
                // 실패가 아니면 곧바로 평소 상태로 돌아간다. 문제가 풀렸는데 천천히 돌아갈 이유가 없다.
                backoff = Duration.ZERO;
            }
            // 4. 종료 줄의 결과 값. Kibana 에서 개수로 거르고 더할 수 있다
            run.result(LogField.result("published", published), LogField.result("quarantined", quarantined),
                    LogField.result("failed", failed), LogField.result("rounds", rounds));

            // 5. 때가 됐으면 밀린 행을 센다. 회차를 다 돈 뒤라 "보내고도 남은 것"이 잡힌다. 쉬는 동안(1번에서 돌아감)은 세지
            // 않는다 — 지표가 내보낼 때 마지막 값의 생성 시각으로 나이를 다시 재므로, Kafka 가 끊긴 동안에도 나이는 커진다.
            // 이 회차의 span 안에서 세므로 그 JDBC span 은 회차의 trace 에 붙고, 빈 회차면 회차와 함께 collector 가 버린다
            measureBacklog();
        });
    }

    /**
     * {@link #BACKLOG_EVERY} 가 지났으면 밀린 행을 세어 지표에 넘긴다.
     *
     * <p>세다가 실패하면(DB 에 닿지 못함) 줄을 남기지 않고 넘어간다. 그때는 회차도 같은 이유로 실패해 실패 줄이 이미 남고,
     * 지표는 마지막 값을 그대로 내보내 나이가 계속 커진다. 다음 간격에 다시 센다.
     */
    private void measureBacklog() {
        Instant now = clock.instant();
        if (now.isBefore(nextBacklogAt)) {
            return;
        }
        // 실패해도 다음 간격까지는 다시 세지 않는다. DB 가 끊긴 동안 1초마다 연결을 기다리지 않게 먼저 정한다
        nextBacklogAt = now.plus(BACKLOG_EVERY);
        try {
            Backlog measured = backlog.get();
            metrics.backlog(measured.pending(), measured.oldest() == null ? null : measured.oldest().toInstant());
        } catch (RuntimeException failure) {
            // 위 설명대로 줄 없이 넘어간다. 실패는 회차의 실패 줄과 지표의 커지는 나이로 드러난다
        }
    }

    /**
     * 다음에 쉴 시간을 정한다. 실패가 이어질수록 두 배씩 늘리되 {@link #MAX_BACKOFF} 를 넘지 않는다.
     *
     * <p>얼마나 오래 갈 문제인지 모를 때 쓰는 방식이다. 곧 풀릴 문제라면 금방 다시 시도하게 되고, 오래 갈 문제라면
     * 몇 번 만에 상한에 닿아 조용해진다.
     *
     * @param current 지금 적용 중인 쉬는 시간. 평소 상태면 {@link Duration#ZERO}
     * @return 처음 실패면 {@link #MIN_BACKOFF}, 그 뒤로는 두 배. 단 {@link #MAX_BACKOFF} 까지
     */
    static Duration nextBackoff(Duration current) {
        if (current.isZero()) {
            return MIN_BACKOFF;
        }
        Duration candidate = current.multipliedBy(2);
        return candidate.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : candidate;
    }

    /**
     * 앱이 종료를 시작하면 Spring 이 부른다. 표시만 켜고 돌아간다 — 돌고 있는 회차는 끊지 않는다.
     *
     * <p>Spring 은 이 이벤트를 먼저 내고, 그다음에 돌고 있는 스케줄 작업이 끝나기를 기다린다. 그래서 표시는
     * 기다림이 시작되기 전에 켜지고, {@link #run()} 은 지금 회차만 마치고 끝난다. 표시가 없으면 밀린 행을 다 비울
     * 때까지 종료가 늦어지고, 30초를 넘기면 회차 도중에 강제로 끊긴다.
     */
    @EventListener(ContextClosedEvent.class)
    void stop() {
        // 스케줄 회차 밖(종료 이벤트)에서 불린다. 어느 작업의 줄인지 작업 이름을 함께 남긴다
        AppLogger.current().info("outbox relay stop", LogField.scheduleName(SCHEDULE));
        stopping = true;
    }
}
