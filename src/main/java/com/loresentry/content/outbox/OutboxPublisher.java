package com.loresentry.content.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * outbox 의 행을 한 묶음 Kafka 로 보낸다. relay 의 한 회차다. 언제, 몇 번 돌릴지는 이 클래스가 정하지 않는다.
 *
 * <p>한 트랜잭션 안에서 행을 잠그고, 보내고, 결과를 기다려, 성공한 행에만 발행 표시를 한다. 도중에 죽으면
 * 트랜잭션이 롤백되어 표시가 남지 않으므로 그 행들은 다음에 다시 나간다 — 유실은 없고 중복만 생긴다
 * (at-least-once). 중복은 소비자가 {@code ce_id} 로 거른다.
 *
 * <p>보내지 못한 행은 둘로 나뉜다. 그 행만의 문제라 다시 보내도 소용없는 것(크기 초과, 계약에 없는 종류)은
 * 격리해서 줄에서 뺀다. 그 밖의 실패(broker 에 닿지 못함, 전송 시간 초과 등)는 횟수와 이유만 남기고 미발행으로
 * 두어 다음 회차에 다시 보낸다.
 */
@Component
class OutboxPublisher {

    /** 로그를 남기는 도구. 로그에는 행의 id 와 종류, 예외의 종류만 적는다 — payload 와 예외 메시지는 적지 않는다. */
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** 한 회차에 보내는 행 수의 상한. 작은 행이 아주 많을 때 한 회차가 지나치게 길어지지 않게 한다. */
    static final int MAX_ROWS = 50;

    /**
     * 한 회차에 읽는 payload 합계의 상한. 본문이 든 행은 3MB 까지 될 수 있어서, 행 수만으로 끊으면 큰 행이
     * 몰렸을 때 pod 의 메모리 한도(512Mi)를 넘는다.
     */
    static final long MAX_BYTES = 16L * 1024 * 1024;

    /**
     * 한 행의 실패가 이 횟수에 이르면 그때부터 회차마다 로그로 알린다. 격리 판단에는 쓰지 않는다 — 횟수로 격리하면
     * broker 장애가 길어질 때 멀쩡한 행이 격리된다.
     */
    static final int WARN_AFTER_ATTEMPTS = 5;

    /**
     * 결과를 기다리는 시간. {@code delivery.timeout.ms}(20초)보다 길어야 한다 — 이쪽이 먼저 포기하면 Kafka 에는
     * 결국 들어갔는데 여기서는 실패로 보고 다음 회차에 또 보낸다. pod 의 종료 대기 시간(30초)보다는 짧아야 한다.
     */
    private static final Duration SEND_WAIT = Duration.ofSeconds(25);

    /** outbox_events 를 읽고 쓰는 창구. 잠금, 조회, 발행 표시, 실패 기록이 모두 여기를 거친다. */
    private final OutboxRepository repository;

    /** Kafka 로 보내는 Spring 의 도구. key 와 value 가 모두 문자열이다(application.yaml 의 serializer 설정). */
    private final KafkaTemplate<String, String> kafkaTemplate;

    /** 생성자가 하나뿐이라 Spring 이 두 객체를 찾아 넣어 준다. */
    OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Kafka 에 넘긴 행 하나.
     *
     * @param event 보낸 행
     * @param result 그 전송의 성공이나 실패를 나중에 알려 줄 객체
     */
    private record Sent(OutboxEvent event, CompletableFuture<SendResult<String, String>> result) {
    }

    /**
     * 이번 회차에 보내지 못한 행 하나.
     *
     * @param event 보내지 못한 행
     * @param cause 실패의 가장 안쪽 원인. Spring 과 Future 가 겉에 씌운 예외는 벗겨 낸 것이다
     * @param quarantine true 면 그 행만의 문제라 격리한다. false 면 미발행으로 두고 다음 회차에 다시 보낸다
     */
    private record Failure(OutboxEvent event, Throwable cause, boolean quarantine) {
    }

    /**
     * 한 묶음을 보낸다. 흐름은 다음과 같다.
     *
     * <ol>
     *   <li>relay 잠금을 얻는다. 못 얻으면 끝낸다.
     *   <li>보낼 행을 읽으면서 잠근다. 없으면 끝낸다.
     *   <li>읽은 행을 순서대로 모두 Kafka 에 보낸다. 결과는 아직 기다리지 않는다.
     *   <li>보낸 순서대로 결과를 기다려, 성공한 행의 id 를 모은다.
     *   <li>성공한 행에 발행 표시를 한다.
     *   <li>보내지 못한 행을 격리하거나, 횟수와 이유를 남긴다.
     * </ol>
     *
     * <p>{@code @Transactional} 이라 이 메서드가 끝날 때 commit 되고, 1번과 2번의 잠금도 그때 함께 풀린다.
     * 전제: 다른 객체가 불러야 한다. Spring 은 밖에서 들어오는 호출에만 트랜잭션을 씌우므로, 이 클래스 안에서
     * 직접 부르면 트랜잭션이 열리지 않는다.
     *
     * @return 곧바로 다음 묶음을 보내도 되면 true. 보낼 행이 없었거나, 다른 인스턴스가 돌리고 있거나, 다시 보내야
     *         할 실패가 있었으면 false — broker 쪽 문제일 수 있으므로 같은 행을 연달아 두드리지 않는다. 격리로
     *         끝난 실패는 다시 보낼 것이 아니므로 false 로 만들지 않는다
     */
    @Transactional
    boolean publishBatch() {
        // 1. 다른 인스턴스가 이미 돌리고 있으면 이번 회차는 건너뛴다.
        if (!repository.tryRelayLock()) {
            return false;
        }
        // 2. 보낼 행을 읽으면서 잠근다. 비어 있으면 할 일이 없으므로 false 를 돌려 다음 주기까지 쉬게 한다.
        List<OutboxEvent> batch = repository.lockPending(MAX_ROWS, MAX_BYTES);
        if (batch.isEmpty()) {
            return false;
        }

        // 3. 전부 먼저 보내고 결과는 그다음에 기다린다. 행마다 보내고 바로 기다리면 메시지가 하나씩 따로 나가
        // producer 가 묶어 보내지 못한다.
        // Kafka 에 넘긴 행들. 넘긴 순서(seq 순)대로 쌓인다.
        List<Sent> inFlight = new ArrayList<>();
        // 보내지 못한 행들. 드러난 순서대로 쌓아 두었다가 6번에서 한꺼번에 DB 에 기록한다.
        List<Failure> failures = new ArrayList<>();
        // 곧바로 다음 회차를 돌리면 안 되는 일이 있었는가. 다시 보내야 할 실패와 종료 신호가 해당한다.
        boolean failed = false;
        for (OutboxEvent event : batch) {
            ProducerRecord<String, String> record;
            try {
                // 행을 topic, key, header, value 를 갖춘 Kafka 레코드로 바꾼다.
                record = OutboxMessages.toRecord(event);
            } catch (IllegalArgumentException unknownType) {
                // 계약에 없는 event_type 이다. 이 줄에서 나는 IllegalArgumentException 은 그 이유뿐이다. 몇 번을
                // 다시 읽어도 같은 결과이므로 격리하고 다음 행으로 넘어간다.
                failures.add(new Failure(event, unknownType, true));
                continue;
            }
            try {
                // send() 는 전송을 맡기고 곧바로 돌아오며, 돌려받은 CompletableFuture 가 나중에 성공이나 실패를
                // 알려 준다.
                inFlight.add(new Sent(event, kafkaTemplate.send(record)));
            } catch (RuntimeException failure) {
                // send() 가 그 자리에서 던졌다. 크기 초과이거나 broker 에 닿지 못한 경우다.
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
                boolean rowProblem = isRowProblem(cause);
                failures.add(new Failure(event, cause, rowProblem));
                if (!rowProblem) {
                    // 행의 문제가 아니므로 뒤의 행도 같은 이유로 실패할 것이다. 여기서 멈춘다 — 계속하면 행마다
                    // max.block.ms 씩 멈추고, 일부만 나가면 같은 프로젝트의 순서가 뒤집힌다.
                    break;
                }
                // 행의 문제면 그 행만 격리하고 다음 행으로 넘어간다.
            }
        }

        // 4. 보낸 순서대로 결과를 기다린다. 성공한 행의 id 만 published 에 모은다.
        List<UUID> published = new ArrayList<>();
        for (Sent sent : inFlight) {
            try {
                // 결과가 올 때까지 최대 SEND_WAIT 동안 멈춘다. 성공이면 다음 줄로 가고, 실패면 예외가 난다.
                sent.result().get(SEND_WAIT.toMillis(), TimeUnit.MILLISECONDS);
                published.add(sent.event().id());
            } catch (InterruptedException interrupted) {
                // 종료 신호다. 발행 실패가 아니므로 남은 행은 기록 없이 미발행으로 두고 더 기다리지 않는다.
                // 흔한 관용구와 달리 interrupt 표시를 다시 켜지 않는다. 표시가 켜진 채로는 virtual thread 에서
                // 바로 뒤의 DB 기록과 commit 이 실패해 이 회차 전체가 rollback 된다. false 를 돌려주면 부르는
                // 쪽이 곧바로 다음 회차를 돌리지 않으므로, 표시가 없어도 종료가 늦어지지 않는다.
                failed = true;
                break;
            } catch (ExecutionException | TimeoutException failure) {
                // ExecutionException 은 Kafka 가 실패를 알려 온 것이고, TimeoutException 은 SEND_WAIT 안에 답이
                // 없어 결과를 모르는 것이다. 멈추지 않고 나머지도 확인한다 — 뒤의 행들은 이미 보낸 상태라, 결과를
                // 보지 않으면 Kafka 에 들어간 행까지 다시 나간다.
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
                failures.add(new Failure(sent.event(), cause, isRowProblem(cause)));
            }
        }

        // 5. 성공한 행에 발행 표시를 한다. 실패한 행이 있어도 예외를 던지지 않는다. 던지면 트랜잭션 전체가
        // 롤백되어, 이미 Kafka 에 들어간 행의 발행 표시까지 사라지고 그 행들이 다음 회차에 다시 나간다.
        if (!published.isEmpty()) {
            repository.markPublished(published);
        }

        // 6. 보내지 못한 행을 기록한다. 발행 표시와 같은 트랜잭션이라 함께 commit 된다.
        // WARN_AFTER_ATTEMPTS 번 이상 실패한 행을 이유별로 센 것. 키는 예외의 클래스 이름, 값은 행의 수다.
        // TreeMap 이라 키의 이름순으로 정렬되어, 로그에 찍히는 순서가 실행마다 같다.
        Map<String, Integer> stuck = new TreeMap<>();
        for (Failure failure : failures) {
            UUID id = failure.event().id();
            // DB 에 남길 이유. 예외의 클래스 이름과 메시지다.
            String error = failure.cause().toString();
            // 로그에 남길 이유. 예외 메시지는 로그에 적지 않으므로 클래스 이름만 쓴다.
            String reason = failure.cause().getClass().getName();
            if (failure.quarantine()) {
                repository.quarantine(id, error);
                // 이 문구로 로그를 찾는다. 바꾸면 조회와 알림이 함께 깨진다.
                log.error("outbox event quarantined: id={}, type={}, reason={}",
                        id, failure.event().eventType(), reason);
            } else {
                failed = true;
                if (repository.markFailed(id, error) >= WARN_AFTER_ATTEMPTS) {
                    // 이 이유의 개수를 1 올린다. 처음 나온 이유면 1 로 시작한다.
                    stuck.merge(reason, 1, Integer::sum);
                }
            }
        }
        // 행마다 찍으면 broker 장애 때 회차당 수십 줄이 되므로, 회차에 한 줄로 줄여 알린다. 대표 한 건이 아니라
        // 이유별 개수를 싣는다 — 서로 다른 문제가 겹쳤을 때 한쪽이 가려지지 않는다. 어느 행인지는 DB 의
        // attempts 와 last_error 로 찾는다. 이 문구로 로그를 찾으므로, 바꾸면 조회와 알림이 함께 깨진다.
        if (!stuck.isEmpty()) {
            log.warn("outbox events still failing: {}", stuck);
        }
        return !failed;
    }

    /**
     * 이 실패가 그 행만의 문제라서 다시 보내도 소용없는지 판단한다. true 면 그 행을 격리한다.
     *
     * <p>행의 문제라고 확실히 알려진 것만 true 다. 지금은 크기 초과 하나다 — producer 가 보내기 전에 거절하거나
     * ({@code max.request.size}), broker 가 받아 보고 거절한다(topic 의 {@code max.message.bytes}).
     *
     * <p>모르는 실패는 false 로 두어 다시 보내게 한다. 잘못 다시 보낸 것은 원인이 사라지면 스스로 나가지만, 잘못
     * 격리한 것은 사람이 풀어 줄 때까지 멈춰 있기 때문이다. Kafka 가 "재시도 불가"로 분류한 예외를 그대로 쓰지
     * 않는 것도 같은 이유다 — 그 안에는 권한 오류나 broker 내부 오류처럼 행의 문제가 아닌 것이 섞여 있다.
     *
     * @param cause 실패의 가장 안쪽 원인
     * @return 격리해야 하면 true
     */
    private static boolean isRowProblem(Throwable cause) {
        return cause instanceof RecordTooLargeException;
    }
}
