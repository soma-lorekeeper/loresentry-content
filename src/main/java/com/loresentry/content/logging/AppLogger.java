package com.loresentry.content.logging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>서비스 코드가 쓰는 것은 {@link #current()}로 꺼낸 객체의 {@code info}·{@code warn}·{@code error}, 흐름 중간에 알게 된 사용자를
 * 알리는 {@link #identify(UUID)}, 결과 줄에 이유를 맡기는 {@link #addToOutcome(LogField...)}, 응답 본문에 싣는 {@link #traceId()}뿐이다.
 * 객체를 만들고 바인딩하는 일(생성자, {@code with}, {@code withTag}, {@code withUser}, {@code bind})은 로깅 팩토리
 * ({@link RequestLogFilter}, {@link LogFlow}, {@link LogScope}, {@link TraceContext})만 하므로 이 패키지 안에서만 보인다(LOG_SCHEMA.md 5절 "만들기").
 *
 * <p>흐름
 * <ol>
 *   <li>시작점(HTTP 요청은 {@link RequestLogFilter}, 스케줄 회차·Kafka 레코드는 {@link LogFlow}, outbox 행은 {@link LogScope})이
 *       그때 아는 값(traceId, spanId, userId)으로 만든다. 지금 span에서 만드는 일은 {@link TraceContext}가 돕는다.</li>
 *   <li>{@link #bind(AppLogger)}로 요청 범위 저장소(ThreadLocal)에 넣는다. 같은 요청의 어느 계층이든 {@link #current()}로 꺼낸다.</li>
 *   <li>레벨은 info·warn·error만 있다. 필드는 {@link LogField}의 정해 둔 이름만 받으므로 원고·제목·검색어 같은 값이 들어갈 자리가 없다.</li>
 * </ol>
 *
 * <p>줄마다 이 객체의 trace·span·user 값과 문맥 필드({@link #MDC_CONTEXT_KEYS})를 MDC에 실어 SLF4J로 넘기고,
 * {@link EcsLogFormatter}가 ECS JSON 한 줄로 만든다. 바인딩하는 동안에도 같은 값이 MDC에 있으므로, 같은 스레드에서 프레임워크가
 * 찍는 줄에도 trace·user와 문맥 필드가 붙는다. 바인딩이 없는 곳에서 찍은 줄은 그때 열려 있는 span의 값만 붙는다.
 * 그 줄을 찍은 위치는 StackWalker로 찾아
 * {@code log.logger}(클래스)·{@code log.origin.function}(메서드)·{@code log.layer}(이름 끝 규칙 또는 {@link LogLayer})·
 * {@code log.package}에 넣는다(LOG_SCHEMA.md 5절 "레이어와 위치").
 *
 * <p>문맥 태그({@link #withTag(String)})는 그 객체로 찍는 모든 줄의 {@code tags}가 된다. 예: 헬스체크 요청의 {@code health}.
 *
 * <p>객체는 바꿀 수 없다. 나중에 아는 값은 {@link #withUser(UUID)}·{@link #with(LogField...)}가 만든 새 객체를
 * 다시 {@link #bind(AppLogger)}한다. 예외는 흐름 공유 칸({@link Shared}) 하나다: 흐름 하나(요청, 스케줄 회차, Kafka 레코드)의 객체들이
 * 같은 칸을 함께 쓴다. 실패 이유({@link #addToOutcome(LogField...)})는 결과 줄(request completed)에 붙고, 흐름 중간에 알게 된 사용자
 * ({@link #identify(UUID)})는 그 뒤의 모든 줄과 결과 줄에 붙는다.
 */
public final class AppLogger {
    /** MDC 키. {@link EcsLogFormatter}가 같은 이름의 ECS 필드로 옮긴다. */
    static final String TRACE_ID = "trace.id";
    static final String SPAN_ID = "span.id";
    static final String USER_ID = "user.id";
    /**
     * 문맥 필드({@link #with(LogField...)}) 중 MDC에도 넣는 이름(LOG_SCHEMA.md 5절 "프레임워크 줄"). 흐름 하나(요청, 스케줄 회차,
     * Kafka 레코드) 동안 바뀌지 않는 값이다. {@link EcsLogFormatter}가 MDC의 이 이름만 꺼내, 같은 스레드에서 프레임워크(Spring,
     * Hibernate, Kafka client 등)가 찍은 줄에도 붙인다. 목록 밖의 문맥 필드는 MDC에 넣지 않으므로 우리 줄에만 붙는다.
     */
    static final List<String> MDC_CONTEXT_KEYS = List.of("http.request.method", "url.path", "http.route",
            "loresentry.schedule.name", "loresentry.kafka.topic", "loresentry.kafka.partition", "loresentry.kafka.offset",
            "loresentry.cloudevent.id", "loresentry.cloudevent.type", "loresentry.cloudevent.subject");
    /** MDC는 문자열만 담는다. 이 이름들은 숫자 필드라 {@link EcsLogFormatter}가 숫자로 되돌려 쓴다(ES 타입이 long이다). */
    static final List<String> MDC_NUMBER_KEYS = List.of("loresentry.kafka.partition", "loresentry.kafka.offset");

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
     * 흐름 하나에서 만든 객체(withUser·with·withTag로 만든 것 포함)가 함께 쓰는 칸. 결과 줄에 붙일 필드, 흐름 중간에 알게 된 사용자,
     * 첫 줄 직전에 남길 시작 줄을 담는다. 바인딩이 없는 기본 객체는 null이라 아무것도 쌓이지 않는다.
     */
    private final Shared shared;
    /** 이 객체로 찍는 모든 줄의 tags(SLF4J marker로 넘긴다). 정해 둔 이름만 쓴다(LOG_SCHEMA.md 2.3절). */
    private final List<String> tags;

    /**
     * 요청·flow 시작점에서 만든다.
     *
     * @param traceId 추적 라이브러리가 만든 현재 trace ID(32 hex). 없으면 null
     * @param spanId  현재 span ID(16 hex). 없으면 null
     * @param userId  auth {@code users.id}. 모르면 null
     */
    AppLogger(String traceId, String spanId, UUID userId) {
        this(traceId, spanId, userId == null ? null : userId.toString(), List.of(), new Shared(), List.of());
    }

    private AppLogger(String traceId, String spanId, String userId, List<LogField> context, Shared shared,
                      List<String> tags) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.userId = userId;
        this.context = context;
        this.shared = shared;
        this.tags = tags;
    }

    /** 지금 스레드에 바인딩된 객체. 없으면 trace 없는 기본 객체를 돌려주므로 null이 아니다. */
    public static AppLogger current() {
        AppLogger bound = CURRENT.get();
        return bound == null ? DETACHED : bound;
    }

    /**
     * 객체를 지금 스레드에 바인딩한다. try-with-resources로 닫으면 이전 객체와 MDC 값으로 되돌린다.
     * MDC에도 값을 넣어 두어, 같은 스레드에서 프레임워크(Spring, Tomcat, Kafka client 등)가 찍는 줄에도 trace·user와 문맥 필드
     * ({@link #MDC_CONTEXT_KEYS})가 붙는다.
     */
    static Binding bind(AppLogger logger) {
        var binding = new Binding(CURRENT.get(), logger.putMdc());
        CURRENT.set(logger);
        return binding;
    }

    /** 사용자를 더한 새 객체. 시작점이 그때 아는 사용자(예: gateway가 보낸 X-User-Id)를 붙일 때 쓴다. */
    AppLogger withUser(UUID userId) {
        return new AppLogger(traceId, spanId, userId == null ? null : userId.toString(), context, shared, tags);
    }

    /** 이 객체로 찍는 모든 줄에 붙일 필드를 더한 새 객체. */
    AppLogger with(LogField... fields) {
        var merged = new ArrayList<>(context);
        for (LogField field : fields) if (field != null) merged.add(field);
        return new AppLogger(traceId, spanId, userId, List.copyOf(merged), shared, tags);
    }

    /**
     * 이 객체로 찍는 모든 줄에 tags 값을 더한 새 객체. 정해 둔 이름만 쓴다(LOG_SCHEMA.md 2.3절).
     * 예: RequestLogFilter가 헬스체크 요청에 {@code health}를 달면 그 요청의 모든 줄에 붙고, Logstash가 info 줄을 버린다.
     */
    AppLogger withTag(String tag) {
        if (tag == null || tags.contains(tag)) return this;
        var merged = new ArrayList<>(tags);
        merged.add(tag);
        return new AppLogger(traceId, spanId, userId, context, shared, List.copyOf(merged));
    }

    /**
     * 흐름 중간에 알게 된 사용자를 알린다. 예: Kafka 탈퇴 이벤트에서 payload를 확인한 뒤의 탈퇴 사용자.
     * 이 흐름의 공유 칸에 넣으므로, 이 뒤에 이 흐름이 찍는 모든 줄과 결과 줄에 {@code user.id}가 붙는다.
     * 같은 스레드의 프레임워크 줄에도 붙도록 MDC에도 넣는다(바인딩이 닫히면 되돌아간다). 바인딩된 흐름이 없으면 아무것도 하지 않는다.
     */
    public void identify(UUID userId) {
        if (shared == null || userId == null) return;
        shared.user = userId.toString();
        MDC.put(USER_ID, shared.user);
    }

    /**
     * 이 요청의 결과 줄(request completed)에 붙일 필드를 맡긴다. 예외 처리기는 실패를 따로 찍지 않고 실패 이유
     * (우리 오류 code)를 여기에 맡긴다. 그래서 실패 요청 하나의 결과는 한 줄이고, 에러 건수가 실패 요청 수와 같다.
     * 바인딩된 요청이 없으면 아무것도 하지 않는다.
     */
    public void addToOutcome(LogField... fields) {
        if (shared == null) return;
        for (LogField field : fields) if (field != null) shared.outcome.add(field);
    }

    /** 맡겨진 결과 필드. 결과 줄을 쓰는 RequestLogFilter가 읽는다. */
    LogField[] outcomeFields() {
        return shared == null ? new LogField[0] : shared.outcome.toArray(LogField[]::new);
    }

    /**
     * 이 흐름의 첫 줄이 찍히기 직전에 한 번 부를 일을 맡긴다. 처리할 일이 있을 때만 줄을 남기는 폴링(LogFlow.polling)이 시작 줄을
     * 늦게 남기는 데 쓴다. 바인딩이 없는 기본 객체면 아무것도 하지 않는다.
     */
    void beforeFirstLine(Runnable startLine) {
        if (shared != null) shared.beforeFirstLine.set(startLine);
    }

    /** 이 줄에 남길 사용자. 흐름 중간에 알린 사용자({@link #identify(UUID)})가 있으면 그 값이다. */
    private String effectiveUser() {
        return shared != null && shared.user != null ? shared.user : userId;
    }

    /** 에러 본문 trace_id에 싣는 값. 없으면 null. */
    public String traceId() {
        return traceId;
    }

    /** 정상 동작의 진행 상황. 어느 계층에서 무엇을 처리 중인지와 도메인 ID를 남긴다. */
    public void info(String message, LogField... fields) {
        emit(null, Level.INFO, message, null, fields);
    }

    /** 처리했지만 예상 밖인 상황(재시도, 충돌, 검증 실패, 건너뛴 이벤트). */
    public void warn(String message, LogField... fields) {
        emit(null, Level.WARN, message, null, fields);
    }

    /** 예외를 함께 남기는 warn. 예외 클래스·메시지·스택이 error.* 필드로 들어간다. */
    public void warn(String message, Throwable cause, LogField... fields) {
        emit(null, Level.WARN, message, cause, fields);
    }

    /** 작업 하나의 실패. 예외 없이 분류만 남길 때는 {@link LogField#errorType(String)}을 함께 넘긴다. */
    public void error(String message, LogField... fields) {
        emit(null, Level.ERROR, message, null, fields);
    }

    /** 예외로 작업 하나가 실패했을 때. 예외 클래스·메시지·스택이 error.* 필드로 들어간다. */
    public void error(String message, Throwable cause, LogField... fields) {
        emit(null, Level.ERROR, message, cause, fields);
    }

    /**
     * 줄 위치(log.logger·log.origin.function·log.layer·log.package)를 찍은 곳 대신 {@code origin} 프레임으로 남기는 줄.
     * 로깅 팩토리({@link LogFlow})가 시작·종료 줄을 그 흐름을 연 서비스 메서드(예: OutboxPurge.run) 자리에 남길 때 쓴다.
     */
    void infoAt(StackWalker.StackFrame origin, String message, LogField... fields) {
        emit(origin, Level.INFO, message, null, fields);
    }

    /** {@link #infoAt}의 warn. */
    void warnAt(StackWalker.StackFrame origin, String message, LogField... fields) {
        emit(origin, Level.WARN, message, null, fields);
    }

    /** {@link #infoAt}의 error. */
    void errorAt(StackWalker.StackFrame origin, String message, LogField... fields) {
        emit(origin, Level.ERROR, message, null, fields);
    }

    /** 로깅 팩토리를 부른 곳: 이 패키지의 팩토리 클래스 프레임을 건너뛴 첫 프레임. 팩토리가 흐름을 열 때 한 번 잡아 둔다. */
    static StackWalker.StackFrame callerOf(Class<?> factory) {
        return WALKER.walk(frames -> frames.dropWhile(f -> f.getDeclaringClass() == AppLogger.class
                || f.getDeclaringClass() == factory).findFirst().orElse(null));
    }

    private void emit(StackWalker.StackFrame origin, Level level, String message, Throwable cause, LogField[] fields) {
        // 호출한 클래스·메서드: AppLogger 자신의 프레임을 건너뛴 첫 프레임. 위치를 따로 받았으면(infoAt 등) 그 프레임이다
        StackWalker.StackFrame caller = origin != null ? origin : WALKER.walk(frames ->
                frames.dropWhile(f -> f.getDeclaringClass() == AppLogger.class).findFirst().orElse(null));
        // SLF4J logger는 찍은 클래스의 것이다. 그래서 logging.level 설정(예: com.loresentry 패키지)이 우리 줄에 그대로 적용된다
        String loggerName = caller == null ? AppLogger.class.getName() : caller.getClassName();
        Logger logger = LoggerFactory.getLogger(loggerName);
        if (!logger.isEnabledForLevel(level)) return;
        // 이 흐름의 첫 줄이면, 맡겨 둔 시작 줄을 먼저 남긴다(폴링). 시작 줄 자신이 다시 부르지 않도록 꺼낸 뒤 비운다
        if (shared != null) {
            Runnable startLine = shared.beforeFirstLine.getAndSet(null);
            if (startLine != null) startLine.run();
        }
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

    /**
     * trace·span·user 값과 문맥 필드({@link #MDC_CONTEXT_KEYS})를 MDC에 넣고, 덮어쓰기 전 값을 돌려준다. 값이 null이면 그 키를 지운다.
     * 이 객체에 없는 문맥 필드도 지우므로, 안쪽에서 다른 객체를 바인딩하면 MDC는 그 객체의 값만 갖는다(닫으면 되돌아온다).
     */
    private Map<String, String> putMdc() {
        Map<String, String> previous = new HashMap<>();
        replace(previous, TRACE_ID, traceId);
        replace(previous, SPAN_ID, spanId);
        replace(previous, USER_ID, effectiveUser());
        for (String key : MDC_CONTEXT_KEYS) replace(previous, key, contextValue(key));
        return previous;
    }

    /** 문맥 필드 중 그 이름의 값(MDC는 문자열만 담는다). 없으면 null. 같은 이름이 둘이면 줄 출력과 같이 먼저 더한 값이다. */
    private String contextValue(String key) {
        for (LogField field : context) {
            for (Map.Entry<String, Object> entry : field.entries()) {
                if (key.equals(entry.getKey()) && entry.getValue() != null) return String.valueOf(entry.getValue());
            }
        }
        return null;
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
    static final class Binding implements AutoCloseable {
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
            // 안쪽 바인딩이 열려 있는 동안 바깥 흐름이 사용자를 알게 됐으면(identify), 되돌린 MDC에도 그 사용자를 다시 넣는다
            if (previous != null && previous.effectiveUser() != null) MDC.put(USER_ID, previous.effectiveUser());
        }
    }

    /** 흐름 하나의 공유 칸. 그 흐름의 객체들이 같은 칸을 쓰고, 채팅 스트리밍처럼 다른 스레드에서도 쓸 수 있어 스레드 안전하다. */
    static final class Shared {
        /** 결과 줄(request completed)에 붙일 필드. */
        final List<LogField> outcome = new CopyOnWriteArrayList<>();
        /** 흐름 중간에 알게 된 사용자({@link AppLogger#identify(UUID)}). 모르면 null. */
        volatile String user;
        /** 이 흐름의 첫 줄 직전에 한 번 부를 일(폴링의 늦은 시작 줄). 없으면 null. */
        final AtomicReference<Runnable> beforeFirstLine = new AtomicReference<>();
    }
}
