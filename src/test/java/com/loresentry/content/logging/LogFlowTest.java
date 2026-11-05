package com.loresentry.content.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.KafkaUtils;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 로깅 팩토리 {@link LogFlow}·{@link LogScope}와 {@link AppLogger#identify}가 흐름의 시작·종료 줄을 규칙대로 남기는지 확인한다
 * (LOG_SCHEMA.md 5절 "만들기", "Kafka 레코드·스케줄 작업의 시작·종료 줄").
 * <ul>
 *   <li>시작·종료 줄이 짝을 이루고, 할 일이 예외로 끝나도 종료(실패) 줄이 남는다</li>
 *   <li>폴링은 줄이 없는 회차에 아무 줄도 남기지 않고, 줄이 있는 회차에 시작·종료 줄을 남긴다</li>
 *   <li>종료 줄에 걸린 시간(event.duration, 나노초 숫자)과 결과 값이 붙는다</li>
 *   <li>시작·종료 줄의 위치는 흐름을 연 서비스 메서드다(log.layer·log.origin.function)</li>
 *   <li>진행 중에 알게 된 대상 ID(with)와 사용자(identify)가 이후 줄과 종료 줄에 붙는다</li>
 *   <li>외부 호출·작업·outbox 행·Kafka 에러 처리는 로깅 팩토리가 span을 열고, 그 줄이 그 span 안에서 찍힌다(LOG_SCHEMA.md 7절)</li>
 *   <li>Kafka 레코드를 처리하기 시작할 때 소비 지연 histogram을 남긴다(docs ALERTING.md 7절)</li>
 * </ul>
 */
class LogFlowTest {
    /**
     * 로깅 팩토리가 여는 span과 남기는 지표를 받는 OpenTelemetry SDK. 운영에서는 agent의 SDK가 받는다. 이 테스트 클래스 동안
     * GlobalOpenTelemetry가 되고, 테스트마다 받은 span과 지표를 비운다.
     */
    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    /** 줄을 찍은 순간의 지금 span. formatter는 줄을 쓰는 순간 이 span으로 trace.id·span.id를 채운다. */
    private final Map<ILoggingEvent, SpanContext> spanAtLine = new IdentityHashMap<>();

    private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            spanAtLine.put(event, Span.current().getSpanContext());
            super.append(event);
        }
    };
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach void attach() { events.start(); root.addAppender(events); }
    @AfterEach void detach() { root.detachAppender(events); }

    private static Map<String, Object> pairs(ILoggingEvent event) {
        Map<String, Object> out = new HashMap<>();
        if (event.getKeyValuePairs() != null) event.getKeyValuePairs().forEach(p -> out.putIfAbsent(p.key, p.value));
        return out;
    }

    private List<String> messages() {
        return events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ILoggingEvent line(String message) {
        return events.list.stream().filter(e -> message.equals(e.getFormattedMessage())).findFirst().orElseThrow();
    }

    /** 스케줄 작업 흉내. scheduler 레이어로 지정해, 시작·종료 줄이 이 클래스 자리에 남는지 본다. */
    @LogLayer("scheduler")
    static final class SamplePurge {
        void run() {
            LogFlow.schedule("sample-purge", run -> {
                AppLogger.current().info("sample rows delete");
                run.result(LogField.result("deleted", 3));
            }, LogField.param("retention_days", 7));
        }

        void fail() {
            LogFlow.schedule("sample-purge", run -> {
                throw new IllegalStateException("secret sql in message");
            });
        }
    }

    @Test
    void aScheduleWritesStartAndEndLinesAtTheSchedulerMethod() {
        new SamplePurge().run();
        assertThat(messages()).containsExactly("schedule run start", "sample rows delete", "schedule run completed");
        Map<String, Object> start = pairs(line("schedule run start"));
        assertThat(start).containsEntry("loresentry.schedule.name", "sample-purge").containsEntry("loresentry.param.retention_days", 7)
                .containsEntry("log.layer", "scheduler").containsEntry("log.origin.function", "run");
        assertThat(line("schedule run start").getLoggerName()).endsWith("LogFlowTest$SamplePurge");
        Map<String, Object> end = pairs(line("schedule run completed"));
        assertThat(end).containsEntry("loresentry.schedule.name", "sample-purge").containsEntry("loresentry.result.deleted", 3)
                .containsEntry("log.layer", "scheduler");
        assertThat(end.get("event.duration")).isInstanceOf(Long.class);
        // 흐름 안에서 찍은 줄에도 작업 이름이 붙는다
        assertThat(pairs(line("sample rows delete"))).containsEntry("loresentry.schedule.name", "sample-purge");
    }

    @Test
    void aFailingScheduleSwallowsTheExceptionAndWritesAFailureLineWithoutTheMessage() {
        new SamplePurge().fail();
        assertThat(messages()).containsExactly("schedule run start", "schedule run failed");
        ILoggingEvent failed = line("schedule run failed");
        assertThat(failed.getLevel().toString()).isEqualTo("ERROR");
        Map<String, Object> fields = pairs(failed);
        assertThat(fields).containsEntry("error.type", "java.lang.IllegalStateException").containsKey("error.stack_trace")
                .containsKey("event.duration");
        // 예외 메시지(SQL·요청 값이 섞일 수 있다)는 어디에도 남지 않는다
        assertThat(String.valueOf(fields.get("error.stack_trace"))).doesNotContain("secret sql");
        assertThat(failed.getThrowableProxy()).isNull();
    }

    @Test
    void aPollingRoundWithoutLinesLeavesNothingAndARoundWithWorkLeavesStartAndEnd() {
        LogFlow.polling("sample-relay", run -> run.result(LogField.result("published", 0)));
        assertThat(events.list).isEmpty();

        LogFlow.polling("sample-relay", run -> {
            AppLogger.current().info("sample batch start");
            run.result(LogField.result("published", 2));
        });
        assertThat(messages()).containsExactly("schedule run start", "sample batch start", "schedule run completed");
        assertThat(pairs(line("schedule run completed"))).containsEntry("loresentry.result.published", 2)
                .containsEntry("loresentry.schedule.name", "sample-relay");
    }

    @Test
    void onlyAnIdlePollingRoundMarksItsSpanForTheCollectorToDrop() {
        // Spring이 @Scheduled 실행마다 여는 span 흉내. 빈 회차의 span에만 표시가 붙고, 일이 있던 회차와 실패한 회차는 그대로 남는다
        Tracer tracer = SdkTracerProvider.builder().build().get("test");

        ReadableSpan idle = inSpan(tracer, () -> LogFlow.polling("sample-relay", run -> run.result(LogField.result("published", 0))));
        ReadableSpan busy = inSpan(tracer, () -> LogFlow.polling("sample-relay", run -> AppLogger.current().info("sample batch start")));
        ReadableSpan failed = inSpan(tracer, () -> LogFlow.polling("sample-relay", run -> run.fail("SAMPLE_FAILED")));

        assertThat(idle.getAttribute(TraceContext.IDLE_POLLING)).isTrue();
        assertThat(busy.getAttribute(TraceContext.IDLE_POLLING)).isNull();
        assertThat(failed.getAttribute(TraceContext.IDLE_POLLING)).isNull();
    }

    /** span을 지금 span으로 두고 할 일을 한 뒤 닫는다. 닫힌 span의 속성을 읽으려고 SDK의 ReadableSpan으로 돌려준다. */
    private static ReadableSpan inSpan(Tracer tracer, Runnable work) {
        Span span = tracer.spanBuilder("scheduled run").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            work.run();
        } finally {
            span.end();
        }
        return (ReadableSpan) span;
    }

    @Test
    void aPollingFailureWritesTheStartLineFirstAndAFailedRoundIsAWarning() {
        LogFlow.polling("sample-relay", run -> {
            throw new IllegalStateException("boom");
        });
        assertThat(messages()).containsExactly("schedule run start", "schedule run failed");

        events.list.clear();
        LogFlow.polling("sample-relay", run -> {
            AppLogger.current().info("sample batch start");
            run.fail("OUTBOX_SEND_FAILED");
        });
        ILoggingEvent failed = line("schedule run failed");
        assertThat(failed.getLevel().toString()).isEqualTo("WARN");
        assertThat(pairs(failed)).containsEntry("error.type", "OUTBOX_SEND_FAILED");
    }

    @Test
    void aKafkaRecordCarriesItsFieldsAndReportsTheOutcomeAndLaterIds() {
        UUID event = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        var record = new ConsumerRecord<String, String>("auth.user.deleted.v1", 1, 9L, "k", "v");
        record.headers().add("ce_id", event.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_type", "UserDeleted".getBytes(StandardCharsets.UTF_8));
        LogFlow.kafkaRecord(record, Set.of("UserDeleted"), "user deleted", flow -> {
            AppLogger.current().identify(user);
            flow.with(LogField.projectId(project));
            AppLogger.current().info("sample handle");
            flow.outcome(LogFlow.Outcome.DUPLICATE);
        });
        assertThat(messages()).containsExactly("user deleted event received", "sample handle", "user deleted event processed");
        assertThat(pairs(line("user deleted event received"))).containsEntry("loresentry.cloudevent.id", event.toString())
                .containsEntry("loresentry.cloudevent.type", "UserDeleted").containsEntry("loresentry.kafka.offset", 9L);
        // 진행 중에 알게 된 사용자와 대상 ID가 이후 줄과 종료 줄에 붙는다
        assertThat(line("sample handle").getMDCPropertyMap()).containsEntry("user.id", user.toString());
        assertThat(pairs(line("sample handle"))).containsEntry("loresentry.project_id", project.toString());
        ILoggingEvent processed = line("user deleted event processed");
        assertThat(processed.getMDCPropertyMap()).containsEntry("user.id", user.toString());
        assertThat(pairs(processed)).containsEntry("loresentry.result.outcome", "DUPLICATE")
                .containsEntry("loresentry.project_id", project.toString()).containsKey("event.duration");
        // 흐름이 끝나면 사용자와 문맥이 MDC에 남지 않는다
        assertThat(org.slf4j.MDC.get("user.id")).isNull();
        assertThat(org.slf4j.MDC.get("loresentry.kafka.topic")).isNull();
    }

    @Test
    void aFailingKafkaRecordRethrowsAfterAWarningEndLine() {
        var record = new ConsumerRecord<String, String>("auth.user.deleted.v1", 0, 1L, "k", "v");
        assertThatThrownBy(() -> LogFlow.kafkaRecord(record, Set.of("UserDeleted"), "user deleted", flow -> {
            throw new IllegalArgumentException("bad payload");
        })).isInstanceOf(IllegalArgumentException.class);
        ILoggingEvent processed = line("user deleted event processed");
        assertThat(processed.getLevel().toString()).isEqualTo("WARN");
        // 실패 종료 줄에도 메시지 없는 스택이 붙는다(warn·error 줄에는 언제나 스택이 있다, LOG_SCHEMA.md 5절)
        assertThat(pairs(processed)).containsEntry("loresentry.result.outcome", "FAILED")
                .containsEntry("error.type", "java.lang.IllegalArgumentException");
        assertThat((String) pairs(processed).get("error.stack_trace")).startsWith("java.lang.IllegalArgumentException\n")
                .doesNotContain("bad payload");
    }

    /** 그 시각(epoch 밀리초)에 보낸 쪽이 만든 레코드. 시각을 정하지 않는 짧은 생성자는 시각이 없는 레코드(-1)를 만든다. */
    private static ConsumerRecord<String, String> recordCreatedAt(String topic, long timestamp) {
        return new ConsumerRecord<>(topic, 0, 5L, timestamp, TimestampType.CREATE_TIME, 1, 1, "k", "v", new RecordHeaders(),
                Optional.empty());
    }

    /** 이 테스트에서 남긴 소비 지연 histogram의 점 하나(속성 조합이 하나뿐일 때). */
    private static HistogramPointData consumeDelay() {
        MetricData delay = OTEL.getMetrics().stream().filter(m -> m.getName().equals(LogFlow.CONSUME_DELAY)).findFirst()
                .orElseThrow(() -> new AssertionError("no metric named " + LogFlow.CONSUME_DELAY));
        assertThat(delay.getUnit()).isEqualTo("s");
        assertThat(delay.getInstrumentationScopeInfo().getName()).isEqualTo(TraceContext.INSTRUMENTATION_SCOPE);
        assertThat(delay.getHistogramData().getPoints()).hasSize(1);
        return delay.getHistogramData().getPoints().iterator().next();
    }

    @Test
    void aKafkaRecordRecordsHowLongItWaitedBeforeProcessing() {
        // 2초 전에 만들어진 레코드를 listener 스레드에서 받았다. Spring Kafka는 listener 스레드에 consumer group을 둔다
        var record = recordCreatedAt("auth.user.deleted.v1", System.currentTimeMillis() - 2_000);
        KafkaUtils.setConsumerGroupId("content.user-deleted");
        try {
            LogFlow.kafkaRecord(record, Set.of("UserDeleted"), "user deleted", flow -> AppLogger.current().info("sample handle"));
            // 시각이 없는 레코드(-1)는 남기지 않는다. 남겼다면 같은 점의 개수가 2가 된다
            LogFlow.kafkaRecord(new ConsumerRecord<String, String>("auth.user.deleted.v1", 0, 6L, "k", "v"), Set.of("UserDeleted"),
                    "user deleted", flow -> { });
        } finally {
            KafkaUtils.clearConsumerGroupId();
        }

        HistogramPointData point = consumeDelay();
        assertThat(point.getCount()).isEqualTo(1);
        // 레코드의 시각부터 처리를 시작할 때까지. 테스트가 도는 시간만큼 2초보다 조금 더 걸린다
        assertThat(point.getSum()).isBetween(2.0, 30.0);
        assertThat(point.getBoundaries()).isEqualTo(LogFlow.CONSUME_DELAY_BUCKETS);
        assertThat(point.getAttributes().asMap()).containsOnly(entry(LogFlow.MESSAGING_DESTINATION, "auth.user.deleted.v1"),
                entry(LogFlow.MESSAGING_CONSUMER_GROUP, "content.user-deleted"));
        // 지표는 줄을 더하지 않는다. 시작·처리·종료 줄 그대로다
        assertThat(messages()).containsExactly("user deleted event received", "sample handle", "user deleted event processed",
                "user deleted event received", "user deleted event processed");
    }

    @Test
    void aKafkaRecordFromAClockAheadOfOursRecordsNoNegativeDelay() {
        // 보낸 쪽의 시계가 1분 빠르다. listener 밖(consumer group 없음)이라 topic만 붙는다
        var record = recordCreatedAt("graph-rag.graph-refresh.completed.v1", System.currentTimeMillis() + 60_000);
        LogFlow.kafkaRecord(record, Set.of("GraphRefreshCompleted"), "graph refresh completed", flow -> { });

        HistogramPointData point = consumeDelay();
        assertThat(point.getCount()).isEqualTo(1);
        assertThat(point.getSum()).isZero();
        assertThat(point.getAttributes().asMap())
                .containsOnly(entry(LogFlow.MESSAGING_DESTINATION, "graph-rag.graph-refresh.completed.v1"));
    }

    @Test
    void aCallWritesOneResultLineAndAServerErrorIsAWarning() throws IOException {
        String value = LogFlow.call("s3", call -> {
            call.status(200);
            return "ok";
        });
        assertThat(value).isEqualTo("ok");
        assertThat(messages()).containsExactly("s3 call completed");
        assertThat(pairs(line("s3 call completed"))).containsEntry("http.response.status_code", 200L).containsKey("event.duration");

        events.list.clear();
        LogFlow.call("s3", call -> call.status(503));
        ILoggingEvent completed = line("s3 call completed");
        assertThat(completed.getLevel().toString()).isEqualTo("WARN");
        assertThat(pairs(completed)).containsEntry("error.type", "UPSTREAM_SERVER_ERROR");

        // 검사 예외도 그대로 다시 던지고 결과 줄을 남긴다
        events.list.clear();
        assertThatThrownBy(() -> LogFlow.call("kms", call -> {
            throw new IOException("network");
        })).isInstanceOf(IOException.class);
        assertThat(pairs(line("kms call failed"))).containsEntry("error.type", "java.io.IOException").containsKey("event.duration");

        // 상대가 답한 실패(AWS SDK 서비스 예외)는 그 응답 상태를 실패 줄에 붙인다
        events.list.clear();
        assertThatThrownBy(() -> LogFlow.call("s3", call -> {
            throw S3Exception.builder().statusCode(403).message("bucket and key in message").build();
        })).isInstanceOf(S3Exception.class);
        Map<String, Object> failed = pairs(line("s3 call failed"));
        assertThat(failed).containsEntry("http.response.status_code", 403L)
                .containsEntry("error.type", S3Exception.class.getName());
        assertThat(String.valueOf(failed.get("error.stack_trace"))).doesNotContain("bucket and key");
    }

    @Test
    void aTaskWritesStartAndEndLinesInTheCurrentFlow() {
        LogFlow.task("sample cleanup", task -> task.result(LogField.result("deleted", 1)), LogField.param("keys", 2));
        assertThat(messages()).containsExactly("sample cleanup start", "sample cleanup completed");
        assertThat(pairs(line("sample cleanup start"))).containsEntry("loresentry.param.keys", 2);

        events.list.clear();
        LogFlow.task("sample cleanup", task -> task.fail("MEDIA_DELETE_FAILED"));
        ILoggingEvent failed = line("sample cleanup failed");
        assertThat(failed.getLevel().toString()).isEqualTo("WARN");
        assertThat(pairs(failed)).containsEntry("error.type", "MEDIA_DELETE_FAILED");
    }

    @Test
    void anOutboxRowScopeCarriesTheRowTraceAndEventFields() {
        UUID id = UUID.randomUUID();
        UUID file = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        try (var row = LogScope.outboxEvent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "outbox-relay", id,
                "FileChanged", file, LogField.projectKey(project.toString()))) {
            row.run(() -> AppLogger.current().info("outbox event published"));
        }
        ILoggingEvent published = line("outbox event published");
        assertThat(published.getMDCPropertyMap()).containsEntry("trace.id", "0af7651916cd43dd8448eb211c80319c");
        assertThat(pairs(published)).containsEntry("loresentry.cloudevent.id", id.toString())
                .containsEntry("loresentry.cloudevent.type", "FileChanged").containsEntry("loresentry.cloudevent.subject", file.toString())
                .containsEntry("loresentry.schedule.name", "outbox-relay").containsEntry("loresentry.project_id", project.toString());
        // UUID가 아닌 event key는 project_id로 남기지 않는다
        assertThat(LogField.projectKey("not-a-uuid")).isNull();
    }

    @Test
    void anOutboxRowContinuesTheStoredTraceInItsOwnSpan() {
        // outbox 행에 저장해 둔 traceparent를 부모로 행의 span을 연다. 행의 줄은 그 span 안에서 찍히고, relay 회차와 다른 trace라 회차
        // span을 link로 남긴다(LOG_SCHEMA.md 7절 "저장했다 나중에 처리")
        Span round = OTEL.getOpenTelemetry().getTracer("test").spanBuilder("OutboxRelay.run").startSpan();
        try (Scope ignored = round.makeCurrent();
             var row = LogScope.outboxEvent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "outbox-relay",
                     UUID.randomUUID(), "FileChanged", UUID.randomUUID())) {
            row.run(() -> AppLogger.current().info("outbox event published"));
        } finally {
            round.end();
        }
        SpanData span = span("outbox FileChanged");
        assertThat(span.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(span.getParentSpanId()).isEqualTo("b7ad6b7169203331");
        assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(span.getLinks()).extracting(link -> link.getSpanContext().getSpanId())
                .containsExactly(round.getSpanContext().getSpanId());
        assertThat(span.getInstrumentationScopeInfo().getName()).isEqualTo(TraceContext.INSTRUMENTATION_SCOPE);
        assertThat(spanAtLine.get(line("outbox event published")).getSpanId()).isEqualTo(span.getSpanId());
    }

    @Test
    void anOutboxRowWithoutAStoredTraceStartsItsOwnTrace() {
        // 추적을 붙이기 전에 쌓인 행(값 없음)이나 형식이 틀린 값은 그 행만 새 trace로 시작한다. relay 회차의 trace를 빌려 쓰지 않는다
        Span round = OTEL.getOpenTelemetry().getTracer("test").spanBuilder("OutboxRelay.run").startSpan();
        try (Scope ignored = round.makeCurrent()) {
            for (String stored : new String[]{null, "not-a-traceparent"}) {
                LogScope.outboxEvent(stored, "outbox-relay", UUID.randomUUID(), "FileChanged", UUID.randomUUID()).close();
            }
        } finally {
            round.end();
        }
        assertThat(OTEL.getSpans()).filteredOn(s -> s.getName().equals("outbox FileChanged")).hasSize(2).allSatisfy(s -> {
            assertThat(s.getParentSpanContext().isValid()).isFalse();
            assertThat(s.getTraceId()).isNotEqualTo(round.getSpanContext().getTraceId());
        });
    }

    @Test
    void aKafkaErrorHandlerScopeContinuesTheRecordTrace() {
        // 에러 처리기는 listener의 span 밖에서 불릴 수 있다. 레코드의 traceparent 헤더를 부모로 span을 열고 그 안에서 줄을 찍는다. 그 안에서
        // DLQ로 보내면 DLQ 레코드의 헤더도 보낸 쪽의 trace를 잇는다
        var record = new ConsumerRecord<String, String>("auth.user.deleted.v1", 0, 3L, "k", "v");
        record.headers().add("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01".getBytes(StandardCharsets.UTF_8));
        record.headers().add("ce_type", "UserDeleted".getBytes(StandardCharsets.UTF_8));
        try (var scope = LogScope.kafkaRecord(record, Set.of("UserDeleted"))) {
            scope.fail("SAMPLE_FAILED");
            scope.run(() -> AppLogger.current().error("inbox event dead-lettered"));
        }
        SpanData span = span("auth.user.deleted.v1 recover");
        assertThat(span.getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(span.getParentSpanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        ILoggingEvent dead = line("inbox event dead-lettered");
        assertThat(spanAtLine.get(dead).getSpanId()).isEqualTo(span.getSpanId());
        assertThat(pairs(dead)).containsEntry("loresentry.kafka.topic", "auth.user.deleted.v1")
                .containsEntry("loresentry.cloudevent.type", "UserDeleted");
    }

    @Test
    void aCallOpensASpanAndWritesItsResultLineInsideIt() throws IOException {
        // 외부 호출 결과 줄의 span.id가 호출을 감싸는 span이다. AWS SDK 계측의 CLIENT span은 그 자식이 된다(LOG_SCHEMA.md 7절 "외부 호출")
        String[] inside = new String[1];
        LogFlow.call("s3", call -> {
            inside[0] = Span.current().getSpanContext().getSpanId();
            return call.status(200);
        });
        SpanData s3 = span("s3 call");
        assertThat(inside[0]).isEqualTo(s3.getSpanId());
        assertThat(s3.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(spanAtLine.get(line("s3 call completed")).getSpanId()).isEqualTo(s3.getSpanId());

        assertThatThrownBy(() -> LogFlow.call("kms", call -> {
            throw new IOException("key arn:secret");
        })).isInstanceOf(IOException.class);
        SpanData kms = span("kms call");
        assertThat(kms.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(kms.getStatus().getDescription()).isEqualTo("java.io.IOException");
        assertThat(spanAtLine.get(line("kms call failed")).getSpanId()).isEqualTo(kms.getSpanId());
    }

    @Test
    void aTaskOpensItsOwnSpan() {
        LogFlow.task("media cleanup", task -> AppLogger.current().info("sample step"));
        SpanData cleanup = span("media cleanup");
        assertThat(spanAtLine.get(line("sample step")).getSpanId()).isEqualTo(cleanup.getSpanId());
        assertThat(spanAtLine.get(line("media cleanup completed")).getSpanId()).isEqualTo(cleanup.getSpanId());
    }

    /** 이 테스트에서 끝난 span 가운데 그 이름의 것. */
    private static SpanData span(String name) {
        return OTEL.getSpans().stream().filter(s -> s.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no span named " + name));
    }
}
