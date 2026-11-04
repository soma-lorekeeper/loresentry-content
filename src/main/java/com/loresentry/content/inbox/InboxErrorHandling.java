package com.loresentry.content.inbox;

import java.nio.charset.StandardCharsets;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.logging.LogScope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer.HeaderNames.HeadersToAdd;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * listener 가 메시지를 처리하지 못했을 때 할 일. 다시 시도하고, 끝내 안 되면 DLQ 로 보낸다. 규칙은 팀 Kafka 설계
 * 문서의 "DLQ" 절을 따른다.
 *
 * <p>메시지 하나가 실패하면 이렇게 흐른다. listener 의 트랜잭션은 이미 rollback 되어 inbox 처리 기록도 남지 않은
 * 상태다.
 * <ol>
 *   <li>계약에 맞지 않는 메시지({@link InvalidEventException})는 다시 해도 같으므로 곧바로 3번으로 간다.
 *   <li>그 밖의 실패는 {@link #inboxBackOff()} 의 간격으로 다시 시도한다. 그동안 그 partition 의 뒤 메시지는 기다린다.
 *   <li>DLQ({@code <원본 topic>.content.dlq})로 보내고, 그 메시지의 offset 을 commit 해 다음 메시지로 넘어간다.
 *   <li>DLQ 로 보내는 것까지 실패하면 건너뛰지 않는다. partition 을 멈추고 1번부터 다시 한다 — 메시지를 잃지 않는다.
 * </ol>
 *
 * <p>DLQ 메시지에는 원래 header({@code ce_*})를 그대로 두고 실패 정보를 붙인다. 원래 위치(topic, partition, offset,
 * 시각과 그 종류)는 spring-kafka 가 붙이는 그대로이고, consumer group 은 뺀다 — DLQ 가 이미 소비자마다 따로라 겹친다.
 * 예외 정보는 여기서 직접 만든다. spring-kafka 의 기본값은 예외 메시지와 stacktrace 를 그대로 싣는데, 거기에는 SQL
 * 이나 실제 값이 섞일 수 있다.
 *
 * <p>로그도 직접 남긴다. spring-kafka 는 재시도 중이라는 한 줄만 남기고, 어느 메시지가 왜 실패했는지는 남기지 않는다.
 * 남기는 것은 위치, 이벤트 id, 예외 클래스 이름뿐이다.
 *
 * <p>Spring Boot 는 context 에 하나뿐인 error handler bean 을 listener container 에 끼운다. 그래서 두 listener 모두
 * 이 규칙을 따른다.
 */
@Configuration(proxyBeanMethods = false)
class InboxErrorHandling {


    /** DLQ 이름의 끝. 계약의 규칙 {@code <원본 topic>.<소비자 서비스>.dlq} 에서 소비자 서비스가 content 다. */
    static final String DEAD_LETTER_SUFFIX = ".content.dlq";

    /**
     * 다시 시도하는 간격. 1초에서 시작해 두 배씩 늘리고 10초에서 멈추며, 5번 다시 시도한다(1·2·4·8·10초, 합 25초).
     * DB 가 잠깐 끊긴 정도의 실패가 지나갈 시간이다. relay 가 실패했을 때 쉬는 간격(1초에서 10초)과 같은 모양이다.
     *
     * <p>간격 하나는 {@code max.poll.interval.ms}(5분)보다 훨씬 짧아야 한다. 기다리는 동안 consumer thread 가 잠들어
     * poll 을 하지 못하기 때문이다. 종료 신호가 오면 기다리던 중이라도 곧 깨어난다.
     *
     * <p>error handler 와 따로 bean 으로 둔 이유는, 테스트가 이것만 짧은 간격으로 바꿔 끼우기 위해서다.
     */
    @Bean
    BackOff inboxBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(10_000);
        // 다시 시도하는 횟수. 첫 시도는 세지 않는다.
        backOff.setMaxAttempts(5);
        return backOff;
    }

    /**
     * 두 listener 의 error handler. 다시 시도하고, 끝내 안 되면 DLQ 로 보내고, 그 과정을 로그로 남긴다.
     *
     * @param kafkaTemplate DLQ 로 보낼 때 쓴다. relay 와 같은 producer 설정(acks=all, idempotence)이다
     * @param inboxBackOff 다시 시도하는 간격
     */
    @Bean
    DefaultErrorHandler inboxErrorHandler(KafkaTemplate<String, String> kafkaTemplate, BackOff inboxBackOff) {
        // 보낼 곳. partition 을 -1 로 두면 spring-kafka 가 비워서 보내고, 원래 key 가 partition 을 정한다. 원본은
        // partition 이 여섯 개일 수 있고 DLQ 는 세 개라 같은 번호로 보낼 수 없다.
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, failure) -> new TopicPartition(record.topic() + DEAD_LETTER_SUFFIX, -1));
        // 원래 위치 header 중 consumer group 만 뺀다.
        deadLetters.excludeHeader(HeadersToAdd.GROUP);
        // 예외 header 는 이 함수만 만든다. 이것을 쓰면 spring-kafka 는 예외 header 를 하나도 만들지 않는다.
        deadLetters.setExceptionHeadersCreator((headers, exception, isKey, names) -> {
            Exception actual = unwrap(exception);
            // 무엇이 던져졌나. 껍데기가 아니라 listener 가 실제로 던진 예외다.
            replace(headers, KafkaHeaders.DLT_EXCEPTION_FQCN, actual.getClass().getName());
            // 그 안의 가장 깊은 원인. 예: DataIntegrityViolationException 안의 PSQLException. 원인이 없으면 같다.
            replace(headers, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, deepest(actual).getClass().getName());
            // 계약 위반이면 어느 검사에 걸렸는지를 적는다. 이 메시지는 받은 값을 담지 않도록 만들었다. 그 밖의 예외는
            // 메시지에 SQL 이나 값이 있을 수 있어 클래스 이름만 적는다.
            String message = actual instanceof InvalidEventException && actual.getMessage() != null
                    ? actual.getMessage()
                    : actual.getClass().getName();
            replace(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE, message);
        });

        // DLQ 로 보내는 일은 그 레코드의 범위 안에서 한다. 범위는 레코드의 traceparent 헤더(보낸 쪽의 trace)를 잇는 span 을 연다. Kafka 발행
        // 계측은 보내는 순간의 지금 문맥으로 traceparent 헤더를 덮어쓰므로, 범위 밖에서 보내면 DLQ 레코드가 보낸 쪽의 trace 를 잃는다
        // (LOG_SCHEMA.md 7절 "저장했다 나중에 처리"). 보낸 결과 줄(보냄, 못 보냄)도 같은 범위에서 남겨 레코드마다 span 이 하나다.
        // 보내지 못하면 예외를 다시 던진다 — spring-kafka 가 partition 을 멈추고 처음부터 다시 시도한다.
        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, failure) -> {
            try (LogScope scope = LogScope.kafkaRecord(record, InboxMessages.TYPES)) {
                scope.fail(reason(failure));
                scope.run(() -> {
                    try {
                        deadLetters.accept(record, failure);
                    } catch (RuntimeException sendFailure) {
                        // DLQ 로 보내지 못했다. 이유는 보내기 쪽의 예외다.
                        AppLogger.current().error("inbox event dead-letter failed",
                                LogField.errorType(deepest(sendFailure).getClass().getName()), LogField.safeStackTrace(sendFailure));
                        throw sendFailure;
                    }
                    // DLQ 로 보냈다. 이 줄을 알림의 근거로 쓴다 — 사람이 원인을 보고 재투입해야 한다.
                    AppLogger.current().error("inbox event dead-lettered", LogField.errorType(reason(failure)),
                            LogField.result("check", check(failure)), LogField.safeStackTrace(unwrap(failure)));
                });
            }
        }, inboxBackOff);
        // 계약에 맞지 않는 메시지는 다시 해도 같다. spring-kafka 는 껍데기와 원인 사슬을 벗겨 가며 이 클래스를 찾는다.
        errorHandler.addNotRetryableExceptions(InvalidEventException.class);
        errorHandler.setRetryListeners(new RetryListener() {

            /** 처리가 실패할 때마다 한 번. 다시 시도하지 않는 예외도 첫 실패에 한 번 불린다. */
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception failure, int deliveryAttempt) {
                // listener 의 span 밖에서 불릴 수 있으므로, 레코드의 traceparent 로 원래 흐름의 trace 를 잇는 범위에서 남긴다.
                // topic·partition·offset·이벤트 필드는 LogScope 가 붙인다
                try (LogScope scope = LogScope.kafkaRecord(record, InboxMessages.TYPES)) {
                    scope.fail(reason(failure));
                    scope.run(() -> AppLogger.current().warn("inbox event failed", LogField.param("attempt", deliveryAttempt),
                            LogField.errorType(reason(failure)), LogField.result("check", check(failure)),
                            LogField.safeStackTrace(unwrap(failure))));
                }
            }
        });
        return errorHandler;
    }

    /**
     * spring-kafka 가 씌운 껍데기를 벗긴다. listener 가 던진 예외는 {@link ListenerExecutionFailedException} 한 겹에
     * 싸여 error handler 와 recoverer 에 온다. 그 이름은 모든 실패에 같아서 아무것도 알려 주지 않는다.
     */
    private static Exception unwrap(Exception exception) {
        if (exception instanceof ListenerExecutionFailedException && exception.getCause() instanceof Exception cause) {
            return cause;
        }
        return exception;
    }

    /** 원인 사슬의 가장 안쪽. 원인이 없으면 자기 자신이다. */
    private static Throwable deepest(Throwable throwable) {
        Throwable current = throwable;
        // 원인이 자기 자신을 가리키는 예외도 있어서, 그때 멈춘다.
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 계약 위반이면 걸린 검사의 종류({@code loresentry.result.check}). 그 밖의 예외이거나 예외가 없으면 null 이라 줄에서
     * 빠진다. 메시지 대신 정해진 낱말만 남겨 종류별로 센다.
     */
    private static InvalidEventException.Check check(Exception failure) {
        return unwrap(failure) instanceof InvalidEventException invalid ? invalid.check() : null;
    }

    /** 로그의 error.type. 껍데기를 벗긴 예외의 클래스 이름이다. 예외가 없으면(넘어오지 않으면) null 이라 줄에서 빠진다. */
    private static String reason(Exception failure) {
        return failure == null ? null : unwrap(failure).getClass().getName();
    }

    /**
     * header 를 하나만 남기고 값을 바꾼다. DLQ 에서 재투입된 메시지가 다시 실패하면 앞서 붙은 실패 정보가 원래
     * header 로 따라오므로, 같은 이름을 지우고 넣는다.
     */
    private static void replace(Headers headers, String name, String value) {
        headers.remove(name);
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
