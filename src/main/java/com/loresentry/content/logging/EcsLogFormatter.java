package com.loresentry.content.logging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모든 로그 줄(우리 AppLogger, Spring·Tomcat 같은 프레임워크)을 LOG_SCHEMA.md 5절의 ECS JSON 한 줄로 만든다.
 * {@code logging.structured.format.console}에 이 클래스 이름을 넣어 쓴다.
 *
 * <p>Boot 내장 {@code ecs} 형식을 쓰지 않는 이유: 내장 형식은 key-value·MDC 이름이 자기가 쓰는 객체(log, error 등)와 겹치면
 * "The name 'log' has already been written"으로 그 줄 전체를 버린다. 그래서 {@code log.origin.function}, {@code error.type}을
 * 함께 낼 수 없다. 여기서는 모든 값을 점 이름 그대로 하나의 중첩 객체에 합쳐서 쓴다.
 *
 * <p>필드
 * <ul>
 *   <li>항상: {@code @timestamp}, {@code log.level}, {@code log.logger}, {@code message}, {@code service.name},
 *       {@code service.version}, {@code process.thread.name}, {@code ecs.version}</li>
 *   <li>trace: AppLogger가 MDC에 넣은 {@code trace.id}·{@code span.id}. 없으면 Micrometer Tracing이 넣은 현재 span
 *       ({@code traceId}·{@code spanId})을 쓴다. 그 밖의 MDC 값은 내보내지 않는다.</li>
 *   <li>사용자: MDC {@code user.id}</li>
 *   <li>key-value: AppLogger가 LogField로 넘긴 값(점 이름을 중첩 객체로)</li>
 *   <li>예외: {@code error.type}(클래스), {@code error.message}, {@code error.stack_trace}</li>
 *   <li>SLF4J marker: {@code tags}</li>
 * </ul>
 *
 * <p>JSON을 만들다 실패해도 줄을 버리지 않고 최소 필드만 담은 줄을 쓴다.
 */
public final class EcsLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    /** Logstash는 "ecs":{...}가 든 줄만 필드로 펼친다(gitops logging/base/logstash/pipelines/app-filter.conf). */
    static final String ECS_VERSION = "8.11";
    /** Spring의 commons-logging 연결이 모든 프레임워크 줄에 붙이는 marker. 분류가 아니라서 tags에 넣지 않는다. */
    private static final String COMMONS_LOGGING_MARKER = "COMMONS-LOGGING";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String serviceName;
    private final String serviceVersion;

    /** Boot가 로깅을 초기화할 때 Environment를 넘겨 만든다. 서비스 이름·버전은 {@code logging.structured.ecs.service.*}에서 읽는다. */
    public EcsLogFormatter(Environment environment) {
        this.serviceName = environment.getProperty("logging.structured.ecs.service.name",
                environment.getProperty("spring.application.name"));
        this.serviceVersion = environment.getProperty("logging.structured.ecs.service.version");
    }

    @Override
    public String format(ILoggingEvent event) {
        try {
            return JSON.writeValueAsString(document(event)) + "\n";
        } catch (RuntimeException failure) {
            return fallback(event, failure);
        }
    }

    private Map<String, Object> document(ILoggingEvent event) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("@timestamp", event.getInstant().toString());
        put(root, "log.level", event.getLevel().toString());
        // log.logger: SLF4J logger 이름. AppLogger로 찍은 줄은 찍은 클래스다
        put(root, "log.logger", event.getLoggerName());
        put(root, "process.thread.name", event.getThreadName());
        put(root, "service.name", serviceName);
        put(root, "service.version", serviceVersion);
        root.put("message", event.getFormattedMessage());

        Map<String, String> mdc = mdc(event);
        put(root, "trace.id", first(mdc.get(AppLogger.TRACE_ID), mdc.get("traceId")));
        put(root, "span.id", first(mdc.get(AppLogger.SPAN_ID), mdc.get("spanId")));
        put(root, "user.id", mdc.get(AppLogger.USER_ID));

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            put(root, "error.type", throwable.getClassName());
            put(root, "error.message", throwable.getMessage());
            put(root, "error.stack_trace", ThrowableProxyUtil.asString(throwable));
        }
        put(root, "ecs.version", ECS_VERSION);
        // key-value는 마지막에 넣는다. 이미 들어간 이름(log.level, ecs.version, 예외의 error.type 등)은 바꾸지 못한다.
        // 예외 없이 error.type을 넘긴 경우(LogField.errorType)는 그 값이 들어간다
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) put(root, pair.key, pair.value);
        }
        List<Marker> markers = event.getMarkerList();
        if (markers != null && !markers.isEmpty()) {
            List<String> tags = new ArrayList<>();
            for (Marker marker : markers) {
                if (!COMMONS_LOGGING_MARKER.equals(marker.getName())) tags.add(marker.getName());
            }
            if (!tags.isEmpty()) put(root, "tags", tags);
        }
        return root;
    }

    /**
     * 점 이름("log.origin.function")을 중첩 객체({"log":{"origin":{"function":..}}})로 넣는다. 값이 null이면 넣지 않는다.
     * 먼저 들어간 값이 이긴다: 같은 이름이 이미 있거나, 중간 이름에 객체가 아닌 값이 있으면(이름 충돌) 이 값은 버린다.
     */
    @SuppressWarnings("unchecked")
    static void put(Map<String, Object> root, String dottedName, Object value) {
        if (dottedName == null || value == null) return;
        String[] parts = dottedName.split("\\.");
        Map<String, Object> node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = node.get(parts[i]);
            if (child == null) {
                child = new LinkedHashMap<String, Object>();
                node.put(parts[i], child);
            } else if (!(child instanceof Map)) {
                return;
            }
            node = (Map<String, Object>) child;
        }
        node.putIfAbsent(parts[parts.length - 1], value);
    }

    /** MDC를 읽지 못해도(MDC 어댑터가 없는 LoggerContext 등) 나머지 필드는 그대로 쓴다. */
    private static Map<String, String> mdc(ILoggingEvent event) {
        try {
            Map<String, String> mdc = event.getMDCPropertyMap();
            return mdc == null ? Map.of() : mdc;
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    private static String first(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    /** 정상 경로가 실패했을 때 쓰는 최소 줄. 값은 문자열 연결이 아니라 Jackson으로 이스케이프한다. */
    private String fallback(ILoggingEvent event, RuntimeException failure) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("@timestamp", event.getInstant().toString());
        put(root, "log.level", event.getLevel().toString());
        put(root, "log.logger", event.getLoggerName());
        put(root, "service.name", serviceName);
        root.put("message", "log formatting failed: " + failure.getClass().getName());
        put(root, "ecs.version", ECS_VERSION);
        try {
            return JSON.writeValueAsString(root) + "\n";
        } catch (RuntimeException ignored) {
            return "{\"message\":\"log formatting failed\",\"ecs\":{\"version\":\"" + ECS_VERSION + "\"}}\n";
        }
    }
}
