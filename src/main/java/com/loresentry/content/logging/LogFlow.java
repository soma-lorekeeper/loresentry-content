package com.loresentry.content.logging;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.KafkaUtils;
import software.amazon.awssdk.core.exception.SdkServiceException;

/**
 * 흐름 하나의 시작·종료 줄을 남기는 로깅 팩토리(LOG_SCHEMA.md 5절 "만들기", "Kafka 레코드·스케줄 작업의 시작·종료 줄").
 *
 * <p>서비스 코드는 흐름의 재료(작업 이름, Kafka 레코드, 호출 대상)와 할 일(람다)만 넘긴다. 로그 객체를 만들고 바인딩하는 일, 걸린
 * 시간을 재는 일, 결과로 레벨을 정하는 일, 시작·종료 줄을 쓰는 일은 모두 여기서 한다. 할 일이 예외로 끝나도 종료 줄이 남는다.
 *
 * <p>흐름 종류
 * <ul>
 *   <li>{@link #schedule}: 하루 한 번 도는 스케줄 작업 1회. 할 일이 없어도 시작·종료 줄을 남긴다
 *       (줄이 없으면 "할 일이 없었다"와 "돌지 않았다"를 가릴 수 없다)</li>
 *   <li>{@link #polling}: 짧은 주기로 도는 폴링 1회(1초 outbox relay, 1분 최신화 시간 제한). 그 회차에서 처음 줄이 찍힐 때
 *       시작 줄을 먼저 남기고, 줄이 하나도 없으면 시작·종료 줄을 모두 남기지 않는다</li>
 *   <li>{@link #kafkaRecord}: Kafka 레코드 하나. 시작은 {@code <event> event received}, 종료는
 *       {@code <event> event processed}와 결과({@code loresentry.result.outcome})다. 처리를 시작할 때 소비 지연 지표
 *       ({@value #CONSUME_DELAY})도 남긴다</li>
 *   <li>{@link #call}: 외부 호출 하나(S3, KMS). 시작은 호출하는 client 메서드의 진입 줄이고, 결과 줄 하나를 남긴다</li>
 *   <li>{@link #task}: 흐름 안·밖의 별도 작업(커밋 뒤 정리, migration). {@code <name> start}와
 *       {@code <name> completed} 또는 {@code <name> failed}</li>
 * </ul>
 *
 * <p>할 일(람다)은 흐름 객체를 받아 진행 중에 알게 된 값을 알린다: {@link #with}(이후 모든 줄에 붙일 대상 ID),
 * {@link #result}(종료 줄의 결과 값), {@link #outcome}(Kafka 레코드의 결과), {@link #status}(호출의 응답 상태),
 * {@link #fail}(예외 없이 끝난 실패의 이유). 흐름 중간에 알게 된 사용자는 {@code AppLogger.current().identify(userId)}로 알린다.
 *
 * <p>시작·종료 줄의 위치(log.logger·log.origin.function·log.layer)는 이 클래스가 아니라 흐름을 연 서비스 메서드다
 * (예: OutboxPurge.run의 scheduler 레이어). 실패 줄에는 예외 메시지를 싣지 않는다. 클래스 이름(error.type)과 메시지 없는
 * 스택만 남긴다(예외 메시지에 SQL·요청 값이 섞일 수 있다).
 */
public final class LogFlow {
    /** Kafka 레코드 하나의 처리 결과. 종료 줄의 loresentry.result.outcome이다. */
    public enum Outcome {
        /** 처음 온 이벤트라 처리했다. 따로 알리지 않으면 이 값이다. */
        PROCESSED,
        /** 이미 처리한 이벤트가 다시 와서 건너뛰었다(inbox 처리 기록). */
        DUPLICATE,
        /** 이 listener가 처리하지 않는 종류라 건너뛰었다. */
        SKIPPED,
        /** 처리 중 예외가 났다. 에러 처리기가 다시 시도하거나 DLQ로 보낸다. */
        FAILED
    }

    /** 흐름 안에서 할 일. 흐름 객체로 진행 중에 알게 된 값을 알린다. */
    @FunctionalInterface
    public interface Body<E extends Exception> {
        void run(LogFlow flow) throws E;
    }

    /** 값을 돌려주는 외부 호출. 흐름 객체로 응답 상태 등을 알린다. */
    @FunctionalInterface
    public interface Call<T, E extends Exception> {
        T run(LogFlow flow) throws E;
    }

    /** 스케줄 작업 1회의 시작·종료 줄 문구. 작업 이름은 문구가 아니라 loresentry.schedule.name 필드에 있다. */
    static final String SCHEDULE_START = "schedule run start";
    static final String SCHEDULE_COMPLETED = "schedule run completed";
    static final String SCHEDULE_FAILED = "schedule run failed";
    /** 상대가 5xx로 답한 호출의 error.type. 응답 상태 자체는 http.response.status_code에 있다. */
    static final String UPSTREAM_SERVER_ERROR = "UPSTREAM_SERVER_ERROR";

    /** Kafka 소비 지연 histogram 이름(docs ALERTING.md 7절). 레코드의 시각부터 이 서비스가 처리를 시작하기까지 지난 초다. */
    static final String CONSUME_DELAY = "loresentry.kafka.consume.delay";
    /**
     * 소비 지연의 버킷 경계(초). 평소의 지연(수십 ms~1초)과 밀림(재시도 25초, 분~시간 단위로 쌓인 lag)을 함께 가른다. agent에 권고로 넘겨
     * 기본 경계(밀리초 단위 요청 지연용) 대신 쓰게 한다.
     */
    static final List<Double> CONSUME_DELAY_BUCKETS = List.of(0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0, 30.0, 60.0, 300.0, 900.0, 3600.0);
    /** 소비 지연의 topic 속성(OpenTelemetry messaging 규약 이름). */
    static final AttributeKey<String> MESSAGING_DESTINATION = AttributeKey.stringKey("messaging.destination.name");
    /** 소비 지연의 consumer group 속성(OpenTelemetry messaging 규약 이름). */
    static final AttributeKey<String> MESSAGING_CONSUMER_GROUP = AttributeKey.stringKey("messaging.consumer.group.name");

    /** 종료 줄의 레벨. */
    private enum Severity { INFO, WARN, ERROR }

    /** 흐름을 연 서비스 메서드의 프레임. 시작·종료 줄의 위치다. */
    private final StackWalker.StackFrame origin;
    /** 걸린 시간을 재는 시작점. 시각이 아니라 흘러간 시간만 재는 값이다. */
    private final long started = System.nanoTime();
    /** 종료 줄에 붙일 결과 값({@link #result}). */
    private final List<LogField> result = new ArrayList<>();
    /** {@link #with}가 연 바인딩. 흐름이 끝날 때 연 반대 순서로 닫는다. */
    private final Deque<AppLogger.Binding> bindings = new ArrayDeque<>();
    /** Kafka 레코드의 결과. 알리지 않으면 PROCESSED다. */
    private Outcome outcome;
    /** 외부 호출의 응답 상태. 모르면 null. */
    private Integer status;
    /** 예외 없이 끝난 실패의 이유({@link #fail}). 실패가 아니면 null. */
    private String failureType;

    private LogFlow(StackWalker.StackFrame origin) {
        this.origin = origin;
    }

    // ---------------------------------------------------------------- 진행 중에 알리는 값

    /**
     * 진행 중에 알게 된 대상 ID(예: 최신화 완료 이벤트의 project_id·refresh.request_id)를 이 흐름의 이후 모든 줄에 붙인다.
     * 종료 줄에도 붙는다.
     */
    public LogFlow with(LogField... fields) {
        bindings.push(AppLogger.bind(AppLogger.current().with(fields)));
        return this;
    }

    /** 종료 줄에 붙일 결과 값(건수 등). 여러 번 부르면 모두 붙는다. */
    public LogFlow result(LogField... fields) {
        for (LogField field : fields) if (field != null) result.add(field);
        return this;
    }

    /** Kafka 레코드의 결과(이미 처리함, 처리하지 않는 종류). 알리지 않으면 PROCESSED다. */
    public LogFlow outcome(Outcome outcome) {
        this.outcome = outcome;
        return this;
    }

    /** 외부 호출의 응답 상태. 5xx면 결과 줄이 warn이 된다. */
    public LogFlow status(int status) {
        this.status = status;
        return this;
    }

    /** 예외 없이 끝난 실패를 알린다(예: 다시 보내야 할 행이 남은 relay 회차). 종료 줄이 warn이 되고 이 값이 error.type이다. */
    public LogFlow fail(String errorType) {
        this.failureType = errorType;
        return this;
    }

    // ---------------------------------------------------------------- 흐름 종류

    /**
     * 하루 한 번 도는 스케줄 작업 1회. agent가 회차마다 연 span으로 새 로그 객체를 만들고 작업 이름(schedule.name)을 붙여
     * 바인딩한다. 시작 줄({@code schedule run start}, 시작할 때 아는 값 {@code start})을 남기고 할 일을 한 뒤 종료 줄
     * ({@code schedule run completed}, 결과 값·걸린 시간)을 남긴다.
     *
     * <p>할 일의 예외는 밖으로 내보내지 않는다. 내보내면 스케줄러가 예외 메시지와 SQL이 든 오류를 찍는다. 대신 실패 줄
     * ({@code schedule run failed}, error)을 남기고, 다음 회차에 다시 한다.
     *
     * @param name  작업 이름(outbox-purge 등). 코드에 정해 둔 이름만 넘긴다
     * @param start 시작할 때 아는 값(예: 보존 기간)
     */
    public static void schedule(String name, Body<RuntimeException> body, LogField... start) {
        LogFlow flow = new LogFlow(AppLogger.callerOf(LogFlow.class));
        AppLogger.Binding bound = AppLogger.bind(TraceContext.forSchedule(name));
        try {
            AppLogger.current().infoAt(flow.origin, SCHEDULE_START, start);
            flow.runScheduled(body, () -> true);
        } finally {
            flow.closeBindings();
            bound.close();
        }
    }

    /**
     * 짧은 주기로 도는 폴링 1회. {@link #schedule}과 같지만, 시작 줄을 그 회차에서 처음 줄이 찍히기 직전에 남긴다. 그래서 할 일이
     * 없는 회차(줄이 하나도 없음)는 시작·종료 줄 모두 남지 않는다. 할 일의 예외는 밖으로 내보내지 않고 시작 줄과 실패 줄을 남긴다.
     *
     * @param name 작업 이름(outbox-relay 등)
     */
    public static void polling(String name, Body<RuntimeException> body) {
        LogFlow flow = new LogFlow(AppLogger.callerOf(LogFlow.class));
        AppLogger logger = TraceContext.forSchedule(name);
        // 시작 줄을 남겼는가. 첫 줄 직전에 한 번 켜진다
        boolean[] began = {false};
        logger.beforeFirstLine(() -> {
            began[0] = true;
            logger.infoAt(flow.origin, SCHEDULE_START);
        });
        AppLogger.Binding bound = AppLogger.bind(logger);
        try {
            flow.runScheduled(body, () -> began[0]);
            // 줄이 하나도 없던 빈 회차(실패도 줄을 남기므로 여기 오지 않는다). 로그처럼 span도 남지 않도록 이 회차의 span에
            // 표시를 달아 collector가 버리게 한다
            if (!began[0]) TraceContext.markIdlePolling();
        } finally {
            flow.closeBindings();
            bound.close();
        }
    }

    /**
     * Kafka 레코드 하나. agent의 Spring Kafka 계측이 연 span(보낸 쪽 traceparent를 이은 것)으로 로그 객체를 만들어 바인딩한다. 그 레코드를
     * 처리하는 모든 줄에 topic·partition·offset과 이벤트의 ID·종류·대상이 붙는다. 시작 줄({@code <event> event received})을 남기고
     * 할 일을 한 뒤 종료 줄({@code <event> event processed}, 결과·걸린 시간)을 남긴다.
     *
     * <p>할 일이 예외로 끝나면 종료 줄을 warn과 결과 FAILED, error.type, 메시지 없는 스택으로 남기고 예외를 그대로 다시 던진다. 에러
     * 처리기가 다시 시도하거나 DLQ로 보낸다(그 줄도 스택을 남긴다. warn·error 줄에는 언제나 스택이 있다, LOG_SCHEMA.md 5절).
     *
     * <p>시작 줄보다 먼저 소비 지연({@value #CONSUME_DELAY})을 지표로 남긴다({@link #recordConsumeDelay}).
     *
     * @param knownTypes 이 서비스가 처리하는 이벤트 종류(ce_type). 목록 밖은 로그에 other로 남는다
     * @param event      문구에 쓰는 이벤트 이름(예: "user deleted")
     */
    public static <E extends Exception> void kafkaRecord(ConsumerRecord<?, ?> record, Set<String> knownTypes, String event,
                                                         Body<E> body) throws E {
        LogFlow flow = new LogFlow(AppLogger.callerOf(LogFlow.class));
        // 처리를 시작하는 지금, 레코드가 만들어진 뒤 얼마나 기다렸는지 지표로 남긴다. 줄과 span은 남기지 않는다
        recordConsumeDelay(record);
        AppLogger.Binding bound = AppLogger.bind(TraceContext.forRecord(Span.current().getSpanContext(), record, knownTypes));
        String processed = event + " event processed";
        try {
            AppLogger.current().infoAt(flow.origin, event + " event received");
            try {
                body.run(flow);
            } catch (Throwable failure) {
                flow.outcome = Outcome.FAILED;
                flow.end(Severity.WARN, processed, failure, true);
                throw failure;
            }
            if (flow.outcome == null) flow.outcome = Outcome.PROCESSED;
            flow.end(flow.failureType == null ? Severity.INFO : Severity.WARN, processed, null, false);
        } finally {
            flow.closeBindings();
            bound.close();
        }
    }

    /**
     * 외부 호출 하나(S3, KMS). 새 로그 객체를 만들지 않고 지금 흐름의 줄로 남는다. 호출을 감싸는 span({@code <target> call})을 열고 그
     * 안에서 할 일을 한다. 시작은 이 호출을 부른 client 메서드의 진입 줄이고, 끝나면 그 span 안에서 결과 줄 하나를 남긴다: 성공은 {@code <target> call completed}(걸린 시간, 응답 상태), 상대의 5xx는 같은 문구의 warn과
     * error.type, {@link #fail}이나 예외는 {@code <target> call failed}(warn, error.type). 예외는 그대로 다시 던진다.
     * 상대가 답한 실패(AWS SDK의 {@link SdkServiceException}: 403, 404, 5xx 등)는 그 응답 상태도 실패 줄에 붙인다.
     *
     * @param target 호출 대상(s3, kms 등). 문구에 쓴다
     * @return 호출이 돌려준 값
     */
    public static <T, E extends Exception> T call(String target, Call<T, E> body) throws E {
        // 호출을 감싸는 span(LOG_SCHEMA.md 7절 "외부 호출"). 계측이 여는 CLIENT span(AWS SDK의 S3·KMS 호출)이 이 span의 자식이 되고,
        // 결과 줄을 이 span 안에서 찍어 결과 줄의 span.id가 이 span이 된다. 그래서 줄 하나로 APM의 그 호출을 바로 찾는다. span을 먼저 열고
        // 흐름 객체(걸린 시간을 재기 시작한다)를 만든다. 반대 순서면 처음 호출에서 tracer를 꺼내는 시간이 event.duration에만 들어간다
        Span span = TraceContext.tracer().spanBuilder(target + " call").startSpan();
        LogFlow flow = new LogFlow(AppLogger.callerOf(LogFlow.class));
        try (Scope ignored = span.makeCurrent()) {
            T value;
            try {
                value = body.run(flow);
            } catch (Throwable failure) {
                // 상대가 답한 실패면 응답 상태를 남긴다. 연결 실패·시간 초과처럼 답이 없으면 0 이라 남기지 않는다
                if (failure instanceof SdkServiceException answered && answered.statusCode() > 0) {
                    flow.status = answered.statusCode();
                }
                flow.end(Severity.WARN, target + " call failed", failure, true);
                failed(span, failure);
                throw failure;
            }
            if (flow.failureType != null) {
                flow.end(Severity.WARN, target + " call failed", null, false);
                span.setStatus(StatusCode.ERROR, flow.failureType);
            } else if (flow.status != null && flow.status >= 500) {
                flow.failureType = UPSTREAM_SERVER_ERROR;
                flow.end(Severity.WARN, target + " call completed", null, false);
                span.setStatus(StatusCode.ERROR, UPSTREAM_SERVER_ERROR);
            } else {
                flow.end(Severity.INFO, target + " call completed", null, false);
            }
            return value;
        } finally {
            flow.closeBindings();
            span.end();
        }
    }

    /**
     * 흐름 안·밖의 별도 작업(커밋 뒤 정리, migration). 새 로그 객체를 만들지 않고 지금 흐름의 줄로 남는다. 작업의 span({@code <name>})을
     * 열고 그 안에서 시작 줄
     * ({@code <name> start}, 시작할 때 아는 값)을 남기고 할 일을 한 뒤 종료 줄({@code <name> completed}, 결과 값·걸린 시간)을
     * 남긴다. {@link #fail}로 알린 실패는 {@code <name> failed}(warn), 예외는 {@code <name> failed}(error, 메시지 없는 스택)이고
     * 예외는 그대로 다시 던진다.
     *
     * @param name  작업 이름(문구에 쓴다. 예: "media cleanup")
     * @param start 시작할 때 아는 값
     */
    public static <E extends Exception> void task(String name, Body<E> body, LogField... start) throws E {
        // 작업 하나의 span(LOG_SCHEMA.md 7절 "별도 작업"). 시작·종료 줄과 작업 안의 호출 span이 이 span 아래에 남는다.
        // 흐름 밖에서 시작한 작업(기동 때 migration)이면 이 span이 root다. 호출과 같이 span을 먼저 열고 걸린 시간을 재기 시작한다
        Span span = TraceContext.tracer().spanBuilder(name).startSpan();
        LogFlow flow = new LogFlow(AppLogger.callerOf(LogFlow.class));
        try (Scope ignored = span.makeCurrent()) {
            AppLogger.current().infoAt(flow.origin, name + " start", start);
            try {
                body.run(flow);
            } catch (Throwable failure) {
                flow.end(Severity.ERROR, name + " failed", failure, true);
                failed(span, failure);
                throw failure;
            }
            if (flow.failureType != null) {
                flow.end(Severity.WARN, name + " failed", null, false);
                span.setStatus(StatusCode.ERROR, flow.failureType);
            } else {
                flow.end(Severity.INFO, name + " completed", null, false);
            }
        } finally {
            flow.closeBindings();
            span.end();
        }
    }

    // ---------------------------------------------------------------- 내부

    /**
     * 레코드의 시각({@link ConsumerRecord#timestamp()}, 보통 보낸 쪽 producer가 만든 시각)부터 지금까지 지난 초를 소비 지연 histogram에
     * 남긴다(docs ALERTING.md 7절). broker에 쌓여 기다린 시간과, 같은 partition의 앞 레코드가 처리·재시도되는 동안 기다린 시간이 모두
     * 들어간다. 에러 처리기가 다시 시도하면 listener가 다시 불려 그 시도의 지연도 따로 남는다.
     *
     * <ul>
     *   <li>시각이 없는 레코드(음수, 예전 형식)는 남기지 않는다</li>
     *   <li>보낸 쪽과 이 서버의 시계가 어긋나 음수가 나오면 0으로 남긴다</li>
     *   <li>속성은 topic({@code messaging.destination.name})과 consumer group({@code messaging.consumer.group.name})이다. group은
     *       Spring Kafka가 listener 스레드에 두는 값({@link KafkaUtils#getConsumerGroupId()})이라 listener 밖(테스트에서 직접 부름)에서는
     *       없고, 그때는 topic만 붙인다</li>
     * </ul>
     *
     * <p>meter와 histogram은 부를 때마다 꺼낸다. 테스트가 SDK를 바꿔 꽂을 수 있게 하려는 것으로 {@link TraceContext#tracer()}와 같은
     * 이유다. SDK가 meter와 같은 이름의 histogram을 하나로 묶어 두므로 부를 때마다 새로 쌓이지 않는다. 지표 전송이 꺼진 환경(prod 기본)에서는
     * agent의 meter가 아무것도 하지 않는다.
     */
    private static void recordConsumeDelay(ConsumerRecord<?, ?> record) {
        if (record.timestamp() < 0) return;
        double seconds = Math.max(0, System.currentTimeMillis() - record.timestamp()) / 1000.0;
        AttributesBuilder attributes = Attributes.builder().put(MESSAGING_DESTINATION, record.topic());
        String group = KafkaUtils.getConsumerGroupId();
        if (group != null) attributes.put(MESSAGING_CONSUMER_GROUP, group);
        GlobalOpenTelemetry.getMeter(TraceContext.INSTRUMENTATION_SCOPE).histogramBuilder(CONSUME_DELAY).setUnit("s")
                .setDescription("Time from a Kafka record's timestamp until this service starts processing it")
                .setExplicitBucketBoundariesAdvice(CONSUME_DELAY_BUCKETS).build()
                .record(seconds, attributes.build());
    }

    /**
     * 스케줄·폴링의 할 일을 하고 종료 줄을 남긴다. 예외는 삼킨다(스케줄러가 예외 메시지를 찍지 않게). 폴링은 시작 줄을 남긴
     * 회차에서만 종료 줄을 남긴다({@code began}).
     */
    private void runScheduled(Body<RuntimeException> body, BooleanSupplier began) {
        try {
            body.run(this);
        } catch (RuntimeException failure) {
            // 실패 줄은 할 일이 없던 폴링 회차에서도 남는다. 이 줄이 첫 줄이라 시작 줄이 먼저 남는다
            end(Severity.ERROR, SCHEDULE_FAILED, failure, true);
            return;
        }
        if (failureType != null) end(Severity.WARN, SCHEDULE_FAILED, null, false);
        else if (began.getAsBoolean()) end(Severity.INFO, SCHEDULE_COMPLETED, null, false);
    }

    /**
     * 종료 줄 하나. 결과 값, Kafka 결과, 응답 상태, 실패 이유(예외면 클래스 이름, 아니면 {@link #fail}의 값), 걸린 시간을 붙인다.
     * 지금 바인딩된 객체로 찍으므로 흐름 문맥과 {@link #with}로 더한 ID가 함께 붙는다.
     *
     * @param stack 예외의 메시지 없는 스택을 붙일지. Kafka 레코드는 에러 처리기가 원인 줄에 남기므로 붙이지 않는다
     */
    private void end(Severity severity, String message, Throwable failure, boolean stack) {
        List<LogField> fields = new ArrayList<>(result);
        if (outcome != null) fields.add(LogField.result("outcome", outcome));
        if (status != null) fields.add(LogField.statusCode(status));
        fields.add(LogField.errorType(failure != null ? failure.getClass().getName() : failureType));
        if (stack) fields.add(LogField.safeStackTrace(failure));
        fields.add(LogField.duration(Duration.ofNanos(System.nanoTime() - started)));
        LogField[] all = fields.toArray(LogField[]::new);
        AppLogger logger = AppLogger.current();
        switch (severity) {
            case INFO -> logger.infoAt(origin, message, all);
            case WARN -> logger.warnAt(origin, message, all);
            case ERROR -> logger.errorAt(origin, message, all);
        }
    }

    /**
     * 우리 span을 예외로 끝난 실패로 표시한다. span에는 예외 종류만 남는다(예외 event의 메시지와 스택은 collector가 지운다. 스택은 같은
     * span.id의 실패 줄 error.stack_trace로 본다).
     */
    private static void failed(Span span, Throwable failure) {
        span.setStatus(StatusCode.ERROR, failure.getClass().getName());
        span.recordException(failure);
    }

    /** {@link #with}가 연 바인딩을 연 반대 순서로 닫는다. */
    private void closeBindings() {
        while (!bindings.isEmpty()) bindings.pop().close();
    }
}
