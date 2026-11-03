package com.loresentry.content.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.MarkerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * 서비스 코드가 로그를 남기는 유일한 통로다(LOG_SCHEMA.md 5절). SLF4J·System.out을 직접 쓰지 않고 이 객체의 메서드만 부른다.
 * gateway의 같은 이름 클래스와 같은 규칙·API다.
 *
 * <p>흐름
 * <ol>
 *   <li>시작점(HTTP 요청은 {@link RequestLogFilter}, Kafka 레코드는 listener, outbox 발행은 행마다)이 그때 아는 값
 *       (traceId, spanId, userId)을 생성자 인자로 넘겨 만든다. 지금 span에서 만드는 일은 {@link TraceContext}가 돕는다.</li>
 *   <li>{@link #bind(AppLogger)}로 요청 범위 저장소(ThreadLocal)에 넣는다. 같은 요청의 어느 계층이든 {@link #current()}로 꺼낸다.</li>
 *   <li>레벨은 info·warn·error만 있다. 필드는 {@link LogField}의 정해 둔 이름만 받으므로 원고·제목·검색어 같은 값이 들어갈 자리가 없다.</li>
 * </ol>
 *
 * <p>줄마다 이 객체의 trace·span·user 값을 MDC에 실어 SLF4J로 넘기고, {@link EcsLogFormatter}가 ECS JSON 한 줄로 만든다.
 * 바인딩이 없는 곳(스케줄 작업 등)에서 찍은 줄은 그때 열려 있는 span의 값이 붙는다. 그 줄을 찍은 위치는 StackWalker로 찾아
 * {@code log.logger}(클래스)·{@code log.origin.function}(메서드)·{@code log.layer}(이름 끝 규칙 또는 {@link LogLayer})·
 * {@code log.package}에 넣는다(LOG_SCHEMA.md 5절 "레이어와 위치").
 *
 * <p>문맥 태그({@link #withTag(String)})는 그 객체로 찍는 모든 줄의 {@code tags}가 된다. 예: 헬스체크 요청의 {@code health}.
 *
 * <p>객체는 바꿀 수 없다. 나중에 아는 값은 {@link #withUser(UUID)}·{@link #with(LogField...)}가 만든 새 객체를
 * 다시 {@link #bind(AppLogger)}한다. 예외는 결과 필드 칸 하나다: 요청 하나의 객체들이 같은 칸을 함께 쓰고,
 * 실패 이유를 {@link #addToOutcome(LogField...)}로 맡기면 결과 줄(request completed)에 붙는다.
 */
public final class AppLogger {
    /** MDC 키. {@link EcsLogFormatter}가 같은 이름의 ECS 필드로 옮긴다. */
    static final String TRACE_ID = "trace.id";
    static final String SPAN_ID = "span.id";
    static final String USER_ID = "user.id";

    private static final ThreadLocal<AppLogger> CURRENT = new ThreadLocal<>();
    /** 바인딩이 없을 때(기동, 스케줄 작업, 프레임워크 콜백) 쓰는 객체. trace는 그때 열려 있는 span이 있으면 그 값을 쓴다. */
    private static final AppLogger DETACHED = new AppLogger(null, null, null, List.of(), null, List.of());
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    /**
     * javac가 람다 본문으로 만드는 메서드 이름: lambda$<람다를 감싼 메서드>$<번호>(예: lambda$send$3).
     * 람다 안에서 찍은 줄의 log.origin.function에는 가운데의 감싼 메서드 이름을 넣는다. 람다 안의 람다도 javac가 같은 모양으로 만든다.
     * 생성자 안 람다는 new, static 초기화 블록 안 람다는 static이 나온다.
     */
    private static final Pattern LAMBDA_METHOD = Pattern.compile("lambda\\$(.+)\\$\\d+");
    /** 클래스 이름 끝 → log.layer. 위에서부터 맞춰 본다(LOG_SCHEMA.md 5절 표와 같다). */
    private static final List<Map.Entry<String, String>> LAYER_SUFFIXES = List.of(
            Map.entry("Controller", "controller"), Map.entry("Service", "service"), Map.entry("Repository", "repository"),
            Map.entry("Store", "repository"), Map.entry("Client", "client"), Map.entry("Listener", "consumer"),
            Map.entry("Consumer", "consumer"), Map.entry("Publisher", "publisher"), Map.entry("Filter", "filter"),
            Map.entry("Handler", "handler"), Map.entry("Interceptor", "interceptor"));
    /** 클래스별 {log.layer, log.package}. 줄마다 annotation·이름을 다시 보지 않게 클래스 단위로 한 번만 계산한다. */
    private static final ClassValue<String[]> LOCATION = new ClassValue<>() {
        @Override
        protected String[] computeValue(Class<?> type) {
            return new String[] {layerOf(type), type.getPackageName()};
        }
    };

    private final String traceId;
    private final String spanId;
    private final String userId;
    /** 이 객체로 찍는 모든 줄에 붙는 필드(예: 요청 하나의 url.path, Kafka 레코드 하나의 topic·offset). */
    private final List<LogField> context;
    /**
     * 결과 줄(request completed)에 붙일 필드. 요청 하나에서 만든 객체(withUser·with로 만든 것 포함)가 같은 목록을 함께 쓴다.
     * 바인딩이 없는 기본 객체는 null이라 쌓이지 않는다.
     */
    private final List<LogField> outcome;
    /** 이 객체로 찍는 모든 줄의 tags(SLF4J marker로 넘긴다). 정해 둔 이름만 쓴다(LOG_SCHEMA.md 2.3절). */
    private final List<String> tags;

    /**
     * 요청·flow 시작점에서 만든다.
     *
     * @param traceId 추적 라이브러리가 만든 현재 trace ID(32 hex). 없으면 null
     * @param spanId  현재 span ID(16 hex). 없으면 null
     * @param userId  auth {@code users.id}. 모르면 null
     */
    public AppLogger(String traceId, String spanId, UUID userId) {
        this(traceId, spanId, userId == null ? null : userId.toString(), List.of(), new CopyOnWriteArrayList<>(), List.of());
    }

    private AppLogger(String traceId, String spanId, String userId, List<LogField> context, List<LogField> outcome,
                      List<String> tags) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.userId = userId;
        this.context = context;
        this.outcome = outcome;
        this.tags = tags;
    }

    /** 지금 스레드에 바인딩된 객체. 없으면 trace 없는 기본 객체를 돌려주므로 null이 아니다. */
    public static AppLogger current() {
        AppLogger bound = CURRENT.get();
        return bound == null ? DETACHED : bound;
    }

    /**
     * 객체를 지금 스레드에 바인딩한다. try-with-resources로 닫으면 이전 객체와 MDC 값으로 되돌린다.
     * MDC에도 값을 넣어 두어, 같은 스레드에서 프레임워크(Spring, Tomcat, Kafka client 등)가 찍는 줄에도 trace·user가 붙는다.
     */
    public static Binding bind(AppLogger logger) {
        var binding = new Binding(CURRENT.get(), logger.putMdc());
        CURRENT.set(logger);
        return binding;
    }

    /** 사용자를 더한 새 객체. */
    public AppLogger withUser(UUID userId) {
        return new AppLogger(traceId, spanId, userId == null ? null : userId.toString(), context, outcome, tags);
    }

    /** 이 객체로 찍는 모든 줄에 붙일 필드를 더한 새 객체. */
    public AppLogger with(LogField... fields) {
        var merged = new ArrayList<>(context);
        for (LogField field : fields) if (field != null) merged.add(field);
        return new AppLogger(traceId, spanId, userId, List.copyOf(merged), outcome, tags);
    }

    /**
     * 이 객체로 찍는 모든 줄에 tags 값을 더한 새 객체. 정해 둔 이름만 쓴다(LOG_SCHEMA.md 2.3절).
     * 예: RequestLogFilter가 헬스체크 요청에 {@code health}를 달면 그 요청의 모든 줄에 붙고, Logstash가 info 줄을 버린다.
     */
    public AppLogger withTag(String tag) {
        if (tag == null || tags.contains(tag)) return this;
        var merged = new ArrayList<>(tags);
        merged.add(tag);
        return new AppLogger(traceId, spanId, userId, context, outcome, List.copyOf(merged));
    }

    /**
     * 이 요청의 결과 줄(request completed)에 붙일 필드를 맡긴다. 예외 처리기는 실패를 따로 찍지 않고 실패 이유
     * (우리 오류 code)를 여기에 맡긴다. 그래서 실패 요청 하나의 결과는 한 줄이고, 에러 건수가 실패 요청 수와 같다.
     * 바인딩된 요청이 없으면 아무것도 하지 않는다.
     */
    public void addToOutcome(LogField... fields) {
        if (outcome == null) return;
        for (LogField field : fields) if (field != null) outcome.add(field);
    }

    /** 맡겨진 결과 필드. 결과 줄을 쓰는 RequestLogFilter가 읽는다. */
    LogField[] outcomeFields() {
        return outcome == null ? new LogField[0] : outcome.toArray(LogField[]::new);
    }

    /** 에러 본문 trace_id에 싣는 값. 없으면 null. */
    public String traceId() {
        return traceId;
    }

    /** 정상 동작의 진행 상황. 어느 계층에서 무엇을 처리 중인지와 도메인 ID를 남긴다. */
    public void info(String message, LogField... fields) {
        emit(Level.INFO, message, null, fields);
    }

    /** 처리했지만 예상 밖인 상황(재시도, 충돌, 검증 실패, 건너뛴 이벤트). */
    public void warn(String message, LogField... fields) {
        emit(Level.WARN, message, null, fields);
    }

    /** 예외를 함께 남기는 warn. 예외 클래스·메시지·스택이 error.* 필드로 들어간다. */
    public void warn(String message, Throwable cause, LogField... fields) {
        emit(Level.WARN, message, cause, fields);
    }

    /** 작업 하나의 실패. 예외 없이 분류만 남길 때는 {@link LogField#errorType(String)}을 함께 넘긴다. */
    public void error(String message, LogField... fields) {
        emit(Level.ERROR, message, null, fields);
    }

    /** 예외로 작업 하나가 실패했을 때. 예외 클래스·메시지·스택이 error.* 필드로 들어간다. */
    public void error(String message, Throwable cause, LogField... fields) {
        emit(Level.ERROR, message, cause, fields);
    }

    private void emit(Level level, String message, Throwable cause, LogField[] fields) {
        // 호출한 클래스·메서드: AppLogger 자신의 프레임을 건너뛴 첫 프레임
        StackWalker.StackFrame caller = WALKER.walk(frames ->
                frames.dropWhile(f -> f.getDeclaringClass() == AppLogger.class).findFirst().orElse(null));
        // SLF4J logger는 찍은 클래스의 것이다. 그래서 logging.level 설정(예: com.loresentry 패키지)이 우리 줄에 그대로 적용된다
        String loggerName = caller == null ? AppLogger.class.getName() : caller.getClassName();
        Logger logger = LoggerFactory.getLogger(loggerName);
        if (!logger.isEnabledForLevel(level)) return;
        // 이 줄 동안만 이 객체의 값으로 MDC를 덮고, 찍은 뒤 원래 값으로 되돌린다
        Map<String, String> previous = putMdc();
        try {
            LoggingEventBuilder event = logger.atLevel(level).setMessage(message);
            if (caller != null) {
                String[] location = LOCATION.get(caller.getDeclaringClass());
                // 람다 본문이면 javac가 붙인 이름 대신 람다를 감싼 메서드 이름을 쓴다
                String function = caller.getMethodName();
                Matcher lambda = LAMBDA_METHOD.matcher(function);
                if (lambda.matches()) function = lambda.group(1);
                event = event.addKeyValue("log.origin.function", function)
                        .addKeyValue("log.layer", location[0]).addKeyValue("log.package", location[1]);
            }
            for (String tag : tags) event = event.addMarker(MarkerFactory.getMarker(tag));
            for (LogField field : context) event = add(event, field);
            for (LogField field : fields) {
                if (field != null) event = add(event, field);
            }
            if (cause != null) event = event.setCause(cause);
            event.log();
        } finally {
            restoreMdc(previous);
        }
    }

    /** 필드 하나의 이름·값 쌍을 모두 key-value로 넘긴다(문자열 입력값은 [redacted]와 길이 두 쌍이다). */
    private static LoggingEventBuilder add(LoggingEventBuilder event, LogField field) {
        for (Map.Entry<String, Object> entry : field.entries()) event = event.addKeyValue(entry.getKey(), entry.getValue());
        return event;
    }

    /**
     * 클래스의 log.layer. {@link LogLayer}가 먼저이고, 없으면 클래스 이름 끝 규칙이다. 안쪽 클래스(익명 클래스 포함)에서 정할 수
     * 없으면 바깥 클래스로 올라가 본다. 끝까지 정하지 못하면 other.
     */
    static String layerOf(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            LogLayer declared = c.getAnnotation(LogLayer.class);
            if (declared != null) return declared.value();
            for (Map.Entry<String, String> rule : LAYER_SUFFIXES) {
                if (c.getSimpleName().endsWith(rule.getKey())) return rule.getValue();
            }
        }
        return "other";
    }

    /** trace·span·user 값을 MDC에 넣고, 덮어쓰기 전 값을 돌려준다. 값이 null이면 그 키를 지운다. */
    private Map<String, String> putMdc() {
        Map<String, String> previous = new HashMap<>();
        replace(previous, TRACE_ID, traceId);
        replace(previous, SPAN_ID, spanId);
        replace(previous, USER_ID, userId);
        return previous;
    }

    private static void replace(Map<String, String> previous, String key, String value) {
        previous.put(key, MDC.get(key));
        if (value == null) MDC.remove(key);
        else MDC.put(key, value);
    }

    private static void restoreMdc(Map<String, String> previous) {
        previous.forEach((key, value) -> {
            if (value == null) MDC.remove(key);
            else MDC.put(key, value);
        });
    }

    /** {@link #bind(AppLogger)}의 범위. 닫으면 이전 객체와 MDC 값으로 되돌린다. */
    public static final class Binding implements AutoCloseable {
        private final AppLogger previous;
        private final Map<String, String> previousMdc;

        private Binding(AppLogger previous, Map<String, String> previousMdc) {
            this.previous = previous;
            this.previousMdc = Collections.unmodifiableMap(previousMdc);
        }

        @Override
        public void close() {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            restoreMdc(previousMdc);
        }
    }
}
