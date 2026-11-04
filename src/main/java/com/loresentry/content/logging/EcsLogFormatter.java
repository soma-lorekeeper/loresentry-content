package com.loresentry.content.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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
 *   <li>trace: 줄을 쓰는 순간의 지금 span({@code Span.current()}, EDOT agent가 연 span이나 로깅 팩토리가 연 우리 span)이다. AppLogger가
 *       MDC에 넣은 흐름의 {@code trace.id}가 있는데 지금 span이 없거나 다른 trace면 흐름의 {@code trace.id}·{@code span.id}를 쓴다
 *       (LOG_SCHEMA.md 7절 "로그 필드"). Logback은 줄을 찍은 스레드에서 바로 이 formatter를 부르므로 지금 span이 그 줄의 span이다.</li>
 *   <li>사용자: MDC {@code user.id}</li>
 *   <li>key-value: AppLogger가 LogField로 넘긴 값(점 이름을 중첩 객체로)</li>
 *   <li>문맥 필드: AppLogger가 바인딩하며 MDC에 넣은 {@link AppLogger#MDC_CONTEXT_KEYS}(요청의 경로·경로 템플릿·메서드, 스케줄 이름,
 *       Kafka 레코드의 topic·partition·offset, 이벤트의 ID·종류·대상). 같은 스레드의 프레임워크 줄에도 붙는다. 그 밖의 MDC 값은 내보내지 않는다.</li>
 *   <li>예외: {@code error.type}(클래스), {@code error.stack_trace}(원인 사슬의 메시지 없는 스택). 예외 메시지에는 SQL·받은 값이 섞일 수
 *       있어 {@code error.message}는 쓰지 않는다(LOG_SCHEMA.md 4절)</li>
 *   <li>예외 없는 warn·error(우리 줄, 프레임워크 줄): 줄을 쓰는 순간의 호출 위치 스택을 {@code error.stack_trace}에 남긴다. 로그만 보고 어느
 *       경로에서 나온 줄인지 알 수 있게 한다(LOG_SCHEMA.md 5절 "warn·error 스택")</li>
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

    /** 메시지 없는 스택에 담는 원인 단계와 단계마다의 프레임 수. LogField.safeStackTrace(우리 줄의 예외 스택)와 같다. */
    private static final int MAX_CAUSES = 8;
    private static final int MAX_FRAMES = 30;

    /**
     * 호출 위치 스택에서 맨 위(가장 최근)부터 건너뛰는 프레임: 이 formatter와 로깅 라이브러리, 로그 패키지. 그 아래 첫 프레임이 줄을 찍은 곳이다.
     * 우리 줄은 서비스 메서드(LogFlow 줄이면 흐름을 연 메서드), 프레임워크 줄은 그 라이브러리의 메서드다.
     */
    private static final List<String> LOGGING_FRAMES = List.of("com.loresentry.content.logging.", "ch.qos.logback.", "org.slf4j.",
            "org.springframework.boot.logging.", "org.springframework.core.log.", "org.apache.commons.logging.", "org.apache.juli.",
            "org.jboss.logging.", "io.netty.util.internal.logging.", "io.opentelemetry.javaagent.", "java.util.logging.");

    private static final StackWalker WALKER = StackWalker.getInstance();

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
        // 줄을 쓰는 순간의 지금 span. 흐름(MDC trace.id)과 같은 trace일 때만 쓰고, 지금 span이 없거나 다른 trace면 흐름의 값이다
        String flowTrace = mdc.get(AppLogger.TRACE_ID);
        SpanContext current = Span.current().getSpanContext();
        if (current.isValid() && (flowTrace == null || flowTrace.equals(current.getTraceId()))) {
            put(root, "trace.id", current.getTraceId());
            put(root, "span.id", current.getSpanId());
        } else {
            put(root, "trace.id", flowTrace);
            put(root, "span.id", mdc.get(AppLogger.SPAN_ID));
        }
        put(root, "user.id", mdc.get(AppLogger.USER_ID));

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            put(root, "error.type", throwable.getClassName());
            put(root, "error.stack_trace", messageFreeStack(throwable));
        }
        put(root, "ecs.version", ECS_VERSION);
        // key-value는 마지막에 넣는다. 이미 들어간 이름(log.level, ecs.version, 예외의 error.type 등)은 바꾸지 못한다.
        // 예외 없이 error.type을 넘긴 경우(LogField.errorType)는 그 값이 들어간다
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) put(root, pair.key, pair.value);
        }
        // 문맥 필드는 key-value 뒤에 넣는다. AppLogger 줄은 key-value에 같은 값이 이미 있으므로 그대로이고,
        // 프레임워크 줄(key-value 없음)은 여기서 흐름의 경로·스케줄 이름·Kafka 레코드 값을 얻는다
        for (String key : AppLogger.MDC_CONTEXT_KEYS) {
            String value = mdc.get(key);
            put(root, key, AppLogger.MDC_NUMBER_KEYS.contains(key) ? number(value) : value);
        }
        // warn·error 줄에는 언제나 스택이 있다. 예외 스택(위의 throwable, 우리 줄이 넘긴 메시지 없는 스택)이 없으면 호출 위치 스택이다.
        // 첫 줄은 그 줄의 error.type(없으면 logger 이름)이라 예외 스택과 구별된다
        if (event.getLevel().isGreaterOrEqual(Level.WARN) && get(root, "error.stack_trace") == null) {
            Object label = get(root, "error.type");
            put(root, "error.stack_trace", logSiteStack(label != null ? label : event.getLoggerName()));
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

    /** MDC 문자열을 숫자로 되돌린다. 숫자가 아니면 null이라 그 필드를 넣지 않는다(줄 전체를 버리지 않는다). */
    private static Long number(String value) {
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** 점 이름으로 이미 넣은 값을 읽는다. 없으면 null. */
    @SuppressWarnings("unchecked")
    static Object get(Map<String, Object> root, String dottedName) {
        Object node = root;
        for (String part : dottedName.split("\\.")) {
            if (!(node instanceof Map)) return null;
            node = ((Map<String, Object>) node).get(part);
        }
        return node;
    }

    /**
     * 예외의 원인 사슬을 메시지 없이 적는다. 단계마다 클래스 이름 한 줄과 프레임({@code   at 클래스.메서드(파일:줄)})이다. 같은 예외를 두 번
     * 돌지 않는다. 우리 줄이 넘기는 LogField.safeStackTrace와 같은 모양이다.
     */
    static String messageFreeStack(IThrowableProxy failure) {
        StringBuilder trace = new StringBuilder();
        Set<IThrowableProxy> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (IThrowableProxy cause = failure; cause != null && visited.size() < MAX_CAUSES && visited.add(cause);
                cause = cause.getCause()) {
            trace.append(cause.getClassName()).append('\n');
            StackTraceElementProxy[] frames = cause.getStackTraceElementProxyArray();
            if (frames == null) continue;
            for (int i = 0; i < frames.length && i < MAX_FRAMES; i++) {
                trace.append("  at ").append(frames[i].getStackTraceElement()).append('\n');
            }
        }
        return trace.toString();
    }

    /**
     * 줄을 쓰는 순간의 호출 위치 스택. 맨 위의 이 formatter·로깅 라이브러리·로그 패키지 프레임을 건너뛰고 그 아래 30프레임까지 적는다. 첫 줄은
     * {@code log site: <label>}이다.
     */
    static String logSiteStack(Object label) {
        StringBuilder trace = new StringBuilder("log site: ").append(label).append('\n');
        WALKER.walk(frames -> {
            frames.dropWhile(frame -> loggingFrame(frame.getClassName())).limit(MAX_FRAMES)
                    .forEach(frame -> trace.append("  at ").append(frame.toStackTraceElement()).append('\n'));
            return null;
        });
        return trace.toString();
    }

    private static boolean loggingFrame(String className) {
        for (String prefix : LOGGING_FRAMES) if (className.startsWith(prefix)) return true;
        return false;
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
