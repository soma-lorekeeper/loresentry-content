package com.loresentry.content.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * </ul>
 */
class LogFlowTest {
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
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
        assertThat(pairs(processed)).containsEntry("loresentry.result.outcome", "FAILED")
                .containsEntry("error.type", "java.lang.IllegalArgumentException").doesNotContainKey("error.stack_trace");
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
            AppLogger.current().info("outbox event published");
        }
        ILoggingEvent published = line("outbox event published");
        assertThat(published.getMDCPropertyMap()).containsEntry("trace.id", "0af7651916cd43dd8448eb211c80319c");
        assertThat(pairs(published)).containsEntry("loresentry.cloudevent.id", id.toString())
                .containsEntry("loresentry.cloudevent.type", "FileChanged").containsEntry("loresentry.cloudevent.subject", file.toString())
                .containsEntry("loresentry.schedule.name", "outbox-relay").containsEntry("loresentry.project_id", project.toString());
        // UUID가 아닌 event key는 project_id로 남기지 않는다
        assertThat(LogField.projectKey("not-a-uuid")).isNull();
    }
}
