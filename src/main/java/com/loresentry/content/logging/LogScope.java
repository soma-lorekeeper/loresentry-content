package com.loresentry.content.logging;

import java.util.Set;
import java.util.UUID;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * 저장했다 나중에 처리하는 일 하나의 범위(LOG_SCHEMA.md 5절 "만들기", 7절 "저장했다 나중에 처리"). 시작·종료 줄은 남기지 않는다.
 *
 * <ul>
 *   <li>{@link #outboxEvent}: outbox 행 하나(발행, 실패 기록, 격리). 그 행을 만든 요청의 trace(행에 저장된 traceparent)를 잇는다</li>
 *   <li>{@link #kafkaRecord}: Kafka 에러 처리기(재시도 줄, DLQ 발행, DLQ 줄). 레코드의 traceparent 헤더로 보낸 쪽의 trace를 잇는다.
 *       listener의 span 밖에서 불릴 수 있어서 범위가 직접 잇는다</li>
 * </ul>
 *
 * <p>범위를 열면 저장된 traceparent를 부모로 한 span(INTERNAL)이 시작된다. 서비스 코드는 할 일을 람다로 넘긴다({@link #call}·{@link #run}).
 * 그동안 이 span이 지금 문맥이 되고 대상의 로그 객체가 바인딩된다. 그래서
 * <ul>
 *   <li>그 안의 모든 줄(부른 repository 줄 포함)이 그 trace와 이 span의 span.id, 문맥 필드로 남는다</li>
 *   <li>그 안의 계측 span(Kafka 발행, JDBC)이 이 span의 자식이 된다. Kafka 발행 계측은 보내는 순간의 지금 문맥으로
 *       {@code traceparent} 헤더를 넣으므로(같은 이름의 헤더는 지우고 다시 넣는다) 받는 서비스가 원래 요청의 trace를 잇는다.
 *       범위 밖에서 보내면 relay 회차나 에러 처리기 스레드의 문맥이 헤더에 실린다</li>
 * </ul>
 *
 * <p>한 대상의 일이 여러 번에 나뉘어도(보내기, 결과 기다린 뒤 줄 남기기, 실패 기록) 같은 범위로 {@link #call}·{@link #run}을 여러 번 부르고,
 * 일이 끝나면 {@link #close}로 span을 닫는다. 대상마다 span은 하나다. 서비스 코드는 span을 다루지 않는다(LOG_SCHEMA.md 5절, ArchUnit).
 *
 * <p>시작·종료가 있는 흐름은 {@link LogFlow}를 쓴다.
 */
public final class LogScope implements AutoCloseable {
    /** 범위 안에서 할 일. 값을 돌려준다(예: Kafka 전송 결과). */
    @FunctionalInterface
    public interface Work<T, E extends Exception> {
        T run() throws E;
    }

    /** 범위 안에서 할 일. 돌려주는 값이 없다. */
    @FunctionalInterface
    public interface Task<E extends Exception> {
        void run() throws E;
    }

    /** 대상의 로그 객체. 범위 안에서 알린 사용자({@code identify})는 이 객체의 공유 칸에 남아 그 대상의 뒤 줄에도 붙는다. */
    private final AppLogger logger;

    /** 대상의 span. {@link #close}가 닫는다. */
    private final Span span;

    private LogScope(AppLogger logger, Span span) {
        this.logger = logger;
        this.span = span;
    }

    /**
     * outbox 행 하나의 범위를 연다. 행에 저장된 traceparent를 부모로 span({@code outbox <event type>})을 시작하고, 그 span과 relay 작업
     * 이름, 이벤트의 ID·종류·대상(ce_id·ce_type·ce_subject), 서비스가 넘긴 도메인 ID로 행의 로그 객체를 만든다. 받는 서비스의 inbox 줄과 같은
     * cloudevent 필드다.
     *
     * <p>traceparent가 없거나 형식이 틀린 행(추적을 붙이기 전에 쌓인 행)은 그 행만 새 trace로 시작한다. relay 회차와 행은 다른 trace라, 회차
     * span을 link로 남겨 APM에서 회차에서 행으로 건너갈 수 있게 한다.
     *
     * @param traceparent 행의 traceparent
     * @param schedule    relay 작업 이름
     * @param ids         이벤트가 가리키는 도메인 ID(예: event key인 project_id)
     */
    public static LogScope outboxEvent(String traceparent, String schedule, UUID id, String type, UUID subject, LogField... ids) {
        Span span = start("outbox " + type, TraceContext.stored(traceparent));
        return new LogScope(TraceContext.forOutboxEvent(span.getSpanContext(), schedule, id, type, subject, ids), span);
    }

    /**
     * Kafka 레코드 하나의 에러 처리 범위를 연다. 레코드의 traceparent 헤더를 부모로 span({@code <topic> recover})을 시작하고,
     * topic·partition·offset과 이벤트의 ID·종류·대상을 붙인 로그 객체를 만든다. listener와 같은 레코드 필드다. 그 안에서 DLQ로 보내면
     * DLQ 레코드의 헤더도 보낸 쪽의 trace를 잇는다.
     *
     * @param knownTypes 이 서비스가 처리하는 이벤트 종류(ce_type). 목록 밖은 로그에 other로 남는다
     */
    public static LogScope kafkaRecord(ConsumerRecord<?, ?> record, Set<String> knownTypes) {
        Span span = start(record.topic() + " recover", TraceContext.stored(TraceContext.header(record, "traceparent")));
        return new LogScope(TraceContext.forRecord(span.getSpanContext(), record, knownTypes), span);
    }

    /**
     * 저장된 문맥을 부모로 span을 시작한다. 저장된 값이 없으면 새 trace다. 지금 span(relay 회차, listener)이 다른 trace면 link로 남긴다.
     * 아직 지금 문맥으로 두지 않는다({@link #call}이 둔다).
     */
    private static Span start(String name, SpanContext stored) {
        SpanContext current = Span.current().getSpanContext();
        SpanBuilder builder = TraceContext.tracer().spanBuilder(name).setSpanKind(SpanKind.INTERNAL);
        if (stored.isValid()) builder.setParent(Context.root().with(Span.wrap(stored)));
        else builder.setNoParent();
        if (current.isValid() && !current.getTraceId().equals(stored.getTraceId())) builder.addLink(current);
        return builder.startSpan();
    }

    /**
     * 범위 안에서 할 일을 하고 그 값을 돌려준다. 그동안 대상의 span이 지금 문맥이고 대상의 로그 객체가 바인딩된다. 예외는 span을 실패로 표시한
     * 뒤 그대로 다시 던진다(span에는 예외 종류만 남는다. 메시지와 스택은 collector가 지운다).
     */
    public <T, E extends Exception> T call(Work<T, E> work) throws E {
        try (Scope ignored = span.makeCurrent(); AppLogger.Binding bound = AppLogger.bind(logger)) {
            return work.run();
        } catch (Throwable failure) {
            span.setStatus(StatusCode.ERROR, failure.getClass().getName());
            span.recordException(failure);
            throw failure;
        }
    }

    /** 돌려주는 값이 없는 {@link #call}. */
    public <E extends Exception> void run(Task<E> task) throws E {
        call(() -> {
            task.run();
            return null;
        });
    }

    /** 예외 없이 끝난 실패를 알린다(예: 격리한 행, 보내지 못한 행, DLQ로 보낸 레코드). 이유는 로그에 남긴 error.type과 같은 값을 넘긴다. */
    public void fail(String errorType) {
        span.setStatus(StatusCode.ERROR, errorType);
    }

    /** 대상의 일이 끝났다. 대상의 span을 닫는다. */
    @Override
    public void close() {
        span.end();
    }
}
