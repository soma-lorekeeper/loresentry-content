package com.loresentry.content.logging;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private TraceContext() {
    }

    /** 지금 span의 ID로 시작점의 로그 객체를 만든다. span이 없으면 trace 없는 객체다. */
    public static AppLogger newLogger() {
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
    public static AppLogger fromTraceparent(String traceparent) {
        Matcher matcher = traceparent == null ? null : TRACEPARENT.matcher(traceparent);
        if (matcher == null || !matcher.matches()) return new AppLogger(null, null, null);
        return new AppLogger(matcher.group(1), matcher.group(2), null);
    }

    /**
     * Kafka 레코드 하나의 시작점 로그 객체. listener 안에서는 listener observation이 연 span의 ID를 쓰고, span이 없으면
     * (에러 처리기 등) 레코드의 traceparent 헤더로 trace를 잇는다. 그 레코드를 처리하는 모든 줄에 topic·partition·offset과
     * 이벤트 ID(ce_id)가 붙는다. 이벤트 ID는 표준 표기 UUID일 때만 남긴다(계약에 맞지 않는 메시지의 헤더는 받은 값 그대로라서).
     */
    public static AppLogger forRecord(ConsumerRecord<?, ?> record) {
        AppLogger logger = Span.current().getSpanContext().isValid() ? newLogger() : fromTraceparent(header(record, "traceparent"));
        return logger.with(LogField.kafkaTopic(record.topic()), LogField.kafkaPartition(record.partition()),
                LogField.kafkaOffset(record.offset()), LogField.cloudeventId(eventId(header(record, "ce_id"))));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** UUID.fromString은 짧은 표기도 받아 주므로 길이부터 본다. */
    private static UUID eventId(String value) {
        if (value == null || value.length() != 36) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }
}
