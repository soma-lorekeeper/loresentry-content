package com.loresentry.content.logging;

import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * 시작·종료 줄 없이 로그 객체만 바인딩하는 범위(LOG_SCHEMA.md 5절 "만들기"). 서비스 코드는 재료만 넘기고 try-with-resources로 연다.
 * 범위 안의 모든 줄(부른 repository 줄 포함)에 그 대상의 trace와 문맥 필드가 붙고, 닫으면 이전 바인딩으로 돌아간다.
 *
 * <ul>
 *   <li>{@link #outboxEvent}: outbox 행 하나의 줄(발행, 실패 기록, 격리). 그 행을 만든 요청의 trace로 남는다</li>
 *   <li>{@link #kafkaRecord}: Kafka 에러 처리기 콜백(재시도, DLQ)의 줄. listener와 같은 레코드 필드가 붙는다</li>
 * </ul>
 *
 * <p>시작·종료가 있는 흐름은 {@link LogFlow}를 쓴다.
 */
public final class LogScope implements AutoCloseable {
    /** 이 범위가 연 바인딩. 닫으면 이전 객체와 MDC 값으로 되돌린다. */
    private final AppLogger.Binding binding;

    private LogScope(AppLogger logger) {
        this.binding = AppLogger.bind(logger);
    }

    /**
     * outbox 행 하나의 범위. 행에 저장된 traceparent(그 행을 만든 요청의 trace), relay 작업 이름, 이벤트의 ID·종류·대상
     * (ce_id·ce_type·ce_subject)과 서비스가 넘긴 도메인 ID를 붙인다. 받는 서비스의 inbox 줄과 같은 cloudevent 필드다.
     *
     * @param traceparent 행의 traceparent. 없으면(추적 전에 쌓인 행) relay 회차의 trace가 붙는다
     * @param schedule    relay 작업 이름
     * @param ids         이벤트가 가리키는 도메인 ID(예: event key인 project_id)
     */
    public static LogScope outboxEvent(String traceparent, String schedule, UUID id, String type, UUID subject, LogField... ids) {
        return new LogScope(TraceContext.forOutboxEvent(traceparent, schedule, id, type, subject, ids));
    }

    /**
     * Kafka 레코드 하나의 범위. listener의 span이 이미 닫힌 뒤 불리는 에러 처리기 콜백에서 쓴다. 레코드의 traceparent 헤더로
     * trace를 잇고 topic·partition·offset과 이벤트의 ID·종류·대상을 붙인다.
     *
     * @param knownTypes 이 서비스가 처리하는 이벤트 종류(ce_type). 목록 밖은 로그에 other로 남는다
     */
    public static LogScope kafkaRecord(ConsumerRecord<?, ?> record, Set<String> knownTypes) {
        return new LogScope(TraceContext.forRecord(record, knownTypes));
    }

    @Override
    public void close() {
        binding.close();
    }
}
