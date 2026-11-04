package com.loresentry.content.logging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/**
 * 지금 열려 있는 span을 로그 시작점과 outbox가 쓰는 모양으로 꺼내고, 로깅 팩토리가 여는 우리 span의 tracer를 준다(LOG_SCHEMA.md 7절).
 *
 * <p>span은 EDOT Java agent가 연다: HTTP 요청 하나(Tomcat 계측, gateway가 보낸 traceparent를 잇는다), Kafka 레코드 하나(Spring Kafka
 * 계측, 보낸 쪽 traceparent를 잇는다), 스케줄 작업 1회(Spring Scheduling 계측), JDBC·S3·KMS·Kafka 발행 호출. 앱은 OpenTelemetry API만
 * 갖고, agent가 그 API를 자기 SDK에 잇는다. 그래서 {@link Span#current()}가 agent가 연 지금 span이고, {@link #tracer()}로 연 우리 span도
 * agent가 내보낸다. agent가 없는 테스트에서는 API가 아무것도 하지 않거나(지금 span 없음), 테스트가 꽂은 OpenTelemetry SDK가 대신한다.
 *
 * <p>서비스 코드가 부르는 것은 {@link #traceparent()}(outbox 행에 저장할 전파 값, 로그가 아니다)뿐이다.
 */
public final class TraceContext {
    /**
     * 로깅 팩토리가 여는 span의 계측 이름(scope.name). gitops {@code logging/tools/apm_consistency.py}가 이 이름으로 우리 span을
     * 알아본다(흐름 밖에서 연 작업 span은 허용된 root다).
     */
    static final String INSTRUMENTATION_SCOPE = "com.loresentry.content.logging";

    /**
     * 할 일이 없던 폴링 회차의 span 표시. EDOT Collector가 이 속성이 true인 span의 trace를 APM에 쓰기 전에 버린다
     * (gitops {@code logging/base/otel-collector/otel.yml}의 tail_sampling, LOG_SCHEMA.md 7절).
     */
    static final AttributeKey<Boolean> IDLE_POLLING = AttributeKey.booleanKey("loresentry.polling.idle");

    /** 저장해 둔 traceparent 값 하나를 W3C 전파기에 헤더 하나처럼 넘기는 어댑터. */
    private static final TextMapGetter<String> STORED_TRACEPARENT = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(String carrier) {
            return List.of("traceparent");
        }

        @Override
        public String get(String carrier, String key) {
            return "traceparent".equals(key) ? carrier : null;
        }
    };

    private TraceContext() {
    }

    /**
     * 로깅 팩토리가 우리 span(외부 호출 감싸기, 작업, outbox 행, Kafka 에러 처리)을 열 때 쓰는 tracer. 실행 중에는 agent의 tracer다.
     * 테스트가 SDK를 바꿔 꽂을 수 있도록 부를 때마다 꺼낸다(GlobalOpenTelemetry가 tracer를 캐시한다).
     */
    static Tracer tracer() {
        return GlobalOpenTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /**
     * 저장해 둔 traceparent(outbox 행 칼럼, Kafka 헤더)를 다른 쪽(원래 요청)의 span 문맥으로 읽는다. W3C 전파기가 형식(version 00,
     * 32·16 hex, 0이 아닌 ID)을 확인한다. 값이 없거나 틀리면 유효하지 않은 문맥이다.
     */
    static SpanContext stored(String traceparent) {
        if (traceparent == null) return SpanContext.getInvalid();
        Context extracted = W3CTraceContextPropagator.getInstance().extract(Context.root(), traceparent, STORED_TRACEPARENT);
        return Span.fromContext(extracted).getSpanContext();
    }

    /**
     * 지금 span에 빈 폴링 회차 표시를 단다. 폴링 회차의 span은 우리 코드가 아니라 agent가 {@code @Scheduled} 실행마다 연다. 그래서 빈
     * 회차에도 span이 생기는데, 끄면 할 일이 있던 회차의 로그도 trace를 잃는다. 대신 빈 회차임을 표시해 collector가 버리게 한다. 빈 회차는
     * 줄도 자식 span도 없어 버려도 끊기는 것이 없다. span이 없으면 아무것도 하지 않는다.
     */
    static void markIdlePolling() {
        Span.current().setAttribute(IDLE_POLLING, true);
    }

    /**
     * 지금 span의 ID로 시작점의 로그 객체를 만든다. span이 없으면 trace 없는 객체다. 줄마다 남는 span.id는 이 값이 아니라 줄을 찍는 순간의
     * 지금 span이다(EcsLogFormatter). 이 값은 지금 span이 없거나 다른 trace일 때 쓰는 흐름의 값이다.
     */
    static AppLogger newLogger() {
        return loggerOf(Span.current().getSpanContext());
    }

    private static AppLogger loggerOf(SpanContext span) {
        return span.isValid() ? new AppLogger(span.getTraceId(), span.getSpanId(), null) : new AppLogger(null, null, null);
    }

    /**
     * 지금 span의 W3C traceparent 값({@code 00-<trace>-<span>-<flags>}, 55자). outbox 행에 저장하면 relay가 그 값으로 행의 span을 열고
     * 그 안에서 발행해, 받는 서비스가 이 요청의 trace를 잇는다. span이 없으면 null이다.
     */
    public static String traceparent() {
        SpanContext span = Span.current().getSpanContext();
        if (!span.isValid()) return null;
        return "00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + span.getTraceFlags().asHex();
    }

    /**
     * Kafka 레코드 하나의 로그 객체. listener 안에서는 agent가 그 레코드에 연 span, 에러 처리기에서는 {@link LogScope#kafkaRecord}가
     * 레코드의 traceparent 헤더로 연 span으로 만든다. 그 레코드를 처리하는 모든 줄에 topic·partition·offset과 이벤트의 ID·종류·대상
     * (ce_id·ce_type·ce_subject)이 붙는다. outbox가 보낸 행 하나의 줄과 같은 cloudevent 필드다. 계약에 맞지 않는 메시지의 헤더는 받은 값
     * 그대로라서, ID·대상은 표준 표기 UUID일 때만 남기고 종류는 이 서비스가 처리하는 종류(knownTypes)만 그대로 남긴다(목록 밖은 other).
     *
     * @param span       그 레코드의 span
     * @param knownTypes 이 서비스가 받아 처리하는 ce_type 목록
     */
    static AppLogger forRecord(SpanContext span, ConsumerRecord<?, ?> record, Set<String> knownTypes) {
        return loggerOf(span).with(LogField.kafkaTopic(record.topic()), LogField.kafkaPartition(record.partition()),
                LogField.kafkaOffset(record.offset()), LogField.cloudeventId(uuid(header(record, "ce_id"))),
                LogField.cloudeventType(header(record, "ce_type"), knownTypes),
                LogField.cloudeventSubject(uuid(header(record, "ce_subject"))));
    }

    /**
     * 스케줄 작업 1회의 시작점 로그 객체. agent가 회차마다 연 span으로 만들고 작업 이름(schedule.name)을 붙여, 이 회차의 모든 줄(부른
     * repository 줄 포함)에 남게 한다.
     */
    static AppLogger forSchedule(String name) {
        return newLogger().with(LogField.scheduleName(name));
    }

    /**
     * outbox 행 하나의 로그 객체. 행의 span({@link LogScope#outboxEvent}가 행에 저장된 traceparent를 부모로 연 span)으로 만들어, 요청의
     * 흐름 끝에 이 이벤트가 어떻게 됐는지(발행, 실패 기록, 격리) 보이게 한다. relay 작업 이름과 이벤트의 ID·종류·대상(ce_id·ce_type·
     * ce_subject), 서비스가 넘긴 도메인 ID(예: event key인 project_id)를 문맥 필드로 붙인다. 받는 서비스의 inbox 줄({@link #forRecord})과
     * 같은 cloudevent 필드다.
     *
     * @param row 행의 span 문맥
     */
    static AppLogger forOutboxEvent(SpanContext row, String schedule, UUID id, String type, UUID subject, LogField... ids) {
        return loggerOf(row).with(LogField.scheduleName(schedule), LogField.cloudeventId(id),
                LogField.cloudeventType(type), LogField.cloudeventSubject(subject)).with(ids);
    }

    /** 레코드 헤더 하나의 값(UTF-8). 없으면 null. */
    static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** UUID.fromString은 짧은 표기도 받아 주므로 길이부터 본다. */
    private static UUID uuid(String value) {
        if (value == null || value.length() != 36) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }
}
