package com.loresentry.content.logging;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/**
 * 지금 열려 있는 span을 로그 시작점과 outbox가 쓰는 모양으로 꺼낸다(LOG_SCHEMA.md 7절).
 *
 * <p>span은 Spring observation이 연다: HTTP 요청 하나(서버 observation 필터), Kafka 레코드 하나(listener observation),
 * 스케줄 작업 1회(스케줄 observation). Micrometer Tracing의 OpenTelemetry bridge는 span을 OpenTelemetry context에 두므로
 * OpenTelemetry API의 {@link Span#current()}로 읽는다. 그래서 이 값이 필요한 곳(outbox 저장소, relay, listener)이 Tracer를
 * 생성자로 받지 않아도 된다.
 */
public final class TraceContext {
    /** W3C traceparent version 00: 00-<trace-id 32 hex>-<parent span-id 16 hex>-<flags 2 hex>. */
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    /**
     * 할 일이 없던 폴링 회차의 span 표시. EDOT Collector가 이 속성이 true인 span을 APM에 쓰기 전에 버린다
     * (gitops {@code logging/base/otel-collector/otel.yml}의 filter, LOG_SCHEMA.md 7절).
     */
    static final AttributeKey<Boolean> IDLE_POLLING = AttributeKey.booleanKey("loresentry.polling.idle");

    private TraceContext() {
    }

    /**
     * 지금 span에 빈 폴링 회차 표시를 단다. 폴링 회차의 span은 우리 코드가 아니라 Spring이 {@code @Scheduled} 실행마다 연다.
     * 그래서 빈 회차에도 span이 생기는데, 끄면 할 일이 있던 회차의 로그도 trace를 잃는다. 대신 빈 회차임을 표시해 collector가
     * 버리게 한다. 빈 회차는 줄도 하위 span도 없어 버려도 끊기는 것이 없다. span이 없으면 아무것도 하지 않는다.
     */
    static void markIdlePolling() {
        Span.current().setAttribute(IDLE_POLLING, true);
    }

    /** 지금 span의 ID로 시작점의 로그 객체를 만든다. span이 없으면 trace 없는 객체다. */
    static AppLogger newLogger() {
        SpanContext span = Span.current().getSpanContext();
        return span.isValid() ? new AppLogger(span.getTraceId(), span.getSpanId(), null) : new AppLogger(null, null, null);
    }

    /**
     * 지금 span의 W3C traceparent 값({@code 00-<trace>-<span>-<flags>}, 55자). outbox 행에 저장하면 relay가 그 값을
     * Kafka 헤더로 실어, 받는 서비스가 이 요청의 trace를 잇는다. span이 없으면 null이다.
     */
    public static String traceparent() {
        SpanContext span = Span.current().getSpanContext();
        if (!span.isValid()) return null;
        return "00-" + span.getTraceId() + "-" + span.getSpanId() + "-" + span.getTraceFlags().asHex();
    }

    /**
     * 저장해 둔 traceparent의 trace로 로그 객체를 만든다. 저장했다 나중에 처리하는 일(outbox 행 발행)과, listener의 span이
     * 닫힌 뒤 불리는 Kafka 에러 처리기가 원래 흐름의 trace로 줄을 남길 때 쓴다. span.id는 그 값을 저장한 쪽의 span이다.
     * 값이 없거나 형식이 틀리면 trace 없는 객체다.
     */
    static AppLogger fromTraceparent(String traceparent) {
        Matcher matcher = traceparent == null ? null : TRACEPARENT.matcher(traceparent);
        if (matcher == null || !matcher.matches()) return new AppLogger(null, null, null);
        return new AppLogger(matcher.group(1), matcher.group(2), null);
    }

    /**
     * Kafka 레코드 하나의 시작점 로그 객체. listener 안에서는 listener observation이 연 span의 ID를 쓰고, span이 없으면
     * (에러 처리기 등) 레코드의 traceparent 헤더로 trace를 잇는다. 그 레코드를 처리하는 모든 줄에 topic·partition·offset과
     * 이벤트의 ID·종류·대상(ce_id·ce_type·ce_subject)이 붙는다. outbox가 보낸 행 하나의 줄과 같은 cloudevent 필드다.
     * 계약에 맞지 않는 메시지의 헤더는 받은 값 그대로라서, ID·대상은 표준 표기 UUID일 때만 남기고 종류는 이 서비스가 처리하는
     * 종류(knownTypes)만 그대로 남긴다(목록 밖은 other).
     *
     * @param knownTypes 이 서비스가 받아 처리하는 ce_type 목록
     */
    static AppLogger forRecord(ConsumerRecord<?, ?> record, Set<String> knownTypes) {
        AppLogger logger = Span.current().getSpanContext().isValid() ? newLogger() : fromTraceparent(header(record, "traceparent"));
        return logger.with(LogField.kafkaTopic(record.topic()), LogField.kafkaPartition(record.partition()),
                LogField.kafkaOffset(record.offset()), LogField.cloudeventId(uuid(header(record, "ce_id"))),
                LogField.cloudeventType(header(record, "ce_type"), knownTypes),
                LogField.cloudeventSubject(uuid(header(record, "ce_subject"))));
    }

    /**
     * 스케줄 작업 1회의 시작점 로그 객체. 스케줄 observation이 회차마다 연 span으로 만들고 작업 이름(schedule.name)을 붙여,
     * 이 회차의 모든 줄(부른 repository 줄 포함)에 남게 한다.
     */
    static AppLogger forSchedule(String name) {
        return newLogger().with(LogField.scheduleName(name));
    }

    /**
     * outbox 행 하나의 로그 객체. 그 행을 만든 요청의 trace(행에 저장된 traceparent)로 만들어, 요청의 흐름 끝에 이 이벤트가
     * 어떻게 됐는지(발행, 실패 기록, 격리) 보이게 한다. traceparent가 없는 행(추적 전에 쌓인 행)은 relay 회차의 trace가 붙는다.
     * relay 작업 이름과 이벤트의 ID·종류·대상(ce_id·ce_type·ce_subject), 서비스가 넘긴 도메인 ID(예: event key인 project_id)를
     * 문맥 필드로 붙인다. 받는 서비스의 inbox 줄({@link #forRecord})과 같은 cloudevent 필드다.
     */
    static AppLogger forOutboxEvent(String traceparent, String schedule, UUID id, String type, UUID subject, LogField... ids) {
        return fromTraceparent(traceparent).with(LogField.scheduleName(schedule), LogField.cloudeventId(id),
                LogField.cloudeventType(type), LogField.cloudeventSubject(subject)).with(ids);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
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
