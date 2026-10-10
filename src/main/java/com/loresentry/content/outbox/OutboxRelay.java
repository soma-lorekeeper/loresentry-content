package com.loresentry.content.outbox;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.function.Supplier;

import com.loresentry.content.outbox.Round.Outcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>{@code kafka.producer.relay.enabled} 가 false 면 이 객체가 만들어지지 않아 relay 가 돌지 않는다. 값을 적지 않으면
 * 켠 것으로 본다. 테스트에서는 꺼 둔다 — 뒤에서 돌면 테스트가 넣은 행을 먼저 가져가 버린다.
 *
 * <p>{@link OutboxPublisher} 와 클래스를 나눈 데에는 이유가 있다. {@code publishBatch} 의 트랜잭션은 다른 객체가
 * 불러야 열린다. 같은 클래스 안에서 부르면 열리지 않는다.
 */
@Component
@ConditionalOnProperty(name = "kafka.producer.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay {

    /** 로그를 남기는 도구. 개수와 예외의 종류만 적는다 — 행의 내용과 예외 메시지는 적지 않는다. */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * 평소 주기(밀리초). 앞 호출이 끝난 뒤 이만큼 지나면 다시 불린다. 이벤트가 outbox 에 들어간 뒤 Kafka 로
     * 나가기까지의 최대 대기 시간이기도 하다. 처리량은 이 값이 아니라 "더 있는 동안 이어서 도는 것"이 정한다.
     */
    static final long PERIOD_MILLIS = 1_000;

    /** 처음 실패했을 때 쉬는 시간. 평소 주기와 같아서, 한 번의 실패는 평소와 다르지 않게 넘어간다. */
    static final Duration MIN_BACKOFF = Duration.ofSeconds(1);

    /**
     * 쉬는 시간의 상한. broker 가 돌아온 뒤 그것을 알아채기까지의 최대 지연이다. 더 길게 잡아도 아끼는 것이 거의
     * 없다 — broker 로의 재접속은 Kafka producer 가 이 값과 무관하게 약 1초마다 시도하고 있다.
     */
    static final Duration MAX_BACKOFF = Duration.ofSeconds(10);

    /** 회차를 한 번 돌리고 그 결과를 돌려주는 함수. 운영에서는 {@link OutboxPublisher#publishBatch()} 다. */
    private final Supplier<Round> round;

    /** 지금 시각을 알려 주는 시계. 테스트가 시간을 손으로 앞당길 수 있게 건네받는다. */
    private final InstantSource clock;

    /** 지금 적용 중인 쉬는 시간. 0 이면 평소 상태다. 실패가 이어지면 {@link #nextBackoff(Duration)} 로 늘어난다. */
    private Duration backoff = Duration.ZERO;

    /** 이 시각 전에는 불려도 회차를 돌지 않는다. 실패한 회차가 끝난 시각에 쉬는 시간을 더한 값이다. */
    private Instant resumeAt = Instant.MIN;

    /**
     * 앱이 종료를 시작했는가. 종료를 진행하는 thread 가 켜고 스케줄러의 thread 가 읽으므로 {@code volatile} 이다.
     * 다른 두 필드는 스케줄러가 호출을 겹치지 않게 차례로 부르므로 그런 보호가 필요 없다.
     */
    private volatile boolean stopping;

    /**
     * 운영에서 Spring 이 쓰는 생성자. 생성자가 둘이라 어느 쪽을 쓸지 {@code @Autowired} 로 알려 준다.
     *
     * <p>{@code publisher} 는 Spring 이 감싼 proxy 다. 그 메서드를 함수로 넘기면 호출이 proxy 를 거치므로
     * 회차마다 트랜잭션이 열린다.
     */
    @Autowired
    OutboxRelay(OutboxPublisher publisher) {
        this(publisher::publishBatch, InstantSource.system());
    }

    /**
     * 테스트가 쓰는 생성자. 회차와 시계를 바꿔 끼울 수 있어서 DB 와 Kafka 없이, 실제로 기다리지 않고 이 클래스의
     * 판단만 확인할 수 있다.
     *
     * @param round 회차를 한 번 돌리는 함수
     * @param clock 지금 시각을 알려 주는 시계
     */
    OutboxRelay(Supplier<Round> round, InstantSource clock) {
        this.round = round;
        this.clock = clock;
    }

    /**
     * 스케줄러가 주기마다 부른다. 흐름은 클래스 설명의 네 단계다.
     *
     * <p>예외를 밖으로 내보내지 않는다. 내보내면 스케줄러가 긴 오류 로그를 주기마다 찍는다. 받아서 실패한 회차처럼
     * 쉬는 시간을 늘린다.
     */
    @Scheduled(fixedDelay = PERIOD_MILLIS)
    void run() {
        // 1. 아직 쉴 시간이면 곧바로 돌아간다. 쉬는 동안의 호출은 이 비교 한 번이 전부다.
        Instant startedAt = clock.instant();
        if (startedAt.isBefore(resumeAt)) {
            return;
        }

        // 2. 회차가 "더 있다"고 하는 동안 이어서 돈다.
        // 이번 호출에서 보낸 행, 격리한 행, 다시 보내야 할 행의 합계와 돈 회차 수. 요약 로그에 쓴다.
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
                // 개수에 더할 것이 없다. 실패한 회차처럼 다뤄 쉬게 한다. 이 문구로 로그를 찾는다.
                log.error("outbox relay round threw: reason={}", failure.getClass().getName());
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
        } else {
            // 실패가 아니면 곧바로 평소 상태로 돌아간다. 문제가 풀렸는데 천천히 돌아갈 이유가 없다.
            backoff = Duration.ZERO;
        }

        // 4. 한 일이 있었을 때만 요약을 한 줄 남긴다. 주기마다 불리므로 매번 남기면 하루 수만 줄이 "0건"으로 찬다.
        // 정상일 때의 흔적이기도 하다 — 이 줄이 없으면 "잘 보내는 중"과 "relay 가 멈춤"을 로그로 가릴 수 없다.
        // 이 문구로 로그를 찾으므로, 바꾸면 조회와 알림이 함께 깨진다.
        if (published + quarantined + failed > 0) {
            long took = Duration.between(startedAt, clock.instant()).toMillis();
            if (outcome == Outcome.FAILED) {
                // 실패로 간격을 늘렸으면 그 간격도 함께 적는다.
                log.info("outbox relay: published={}, quarantined={}, failed={}, rounds={}, took={}ms, backoff={}s",
                        published, quarantined, failed, rounds, took, backoff.toSeconds());
            } else {
                log.info("outbox relay: published={}, quarantined={}, failed={}, rounds={}, took={}ms",
                        published, quarantined, failed, rounds, took);
            }
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
        stopping = true;
    }
}
