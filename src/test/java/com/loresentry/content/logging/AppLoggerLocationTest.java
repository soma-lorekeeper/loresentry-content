package com.loresentry.content.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * AppLogger가 줄마다 찍은 위치(log.layer·log.package·log.origin.function)와 문맥 태그를 넣는지 확인한다(LOG_SCHEMA.md 5절).
 * 레이어는 @LogLayer, 클래스 이름 끝 규칙, 바깥 클래스 순서로 정한다.
 */
class AppLoggerLocationTest {
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach void attach() { events.start(); root.addAppender(events); }
    @AfterEach void detach() { root.detachAppender(events); }

    private Map<String, Object> pairs() {
        ILoggingEvent last = events.list.getLast();
        Map<String, Object> out = new HashMap<>();
        last.getKeyValuePairs().forEach(p -> out.put(p.key, p.value));
        return out;
    }

    static final class SampleController { void get() { AppLogger.current().info("sample get requested"); } }
    static final class SampleService { void get() { AppLogger.current().info("sample get"); } }
    static final class SampleListener { void receive() { AppLogger.current().info("sample event received"); } }
    static final class SamplePublisher { void publish() { AppLogger.current().info("sample publish"); } }
    static final class SampleStore { void read() { AppLogger.current().info("sample read"); } }
    @LogLayer("scheduler") static final class SamplePurge { void run() { AppLogger.current().info("sample purge start"); } }
    static final class Helper { void run() { AppLogger.current().info("helper"); } }
    static final class SampleRepository {
        // 람다 안에서 찍어도 바깥 클래스의 레이어와 람다를 감싼 메서드 이름이 나와야 한다
        void read() { Supplier<String> read = () -> { AppLogger.current().info("sample read"); return "x"; }; read.get(); }
    }

    @Test
    void layerComesFromTheClassNameSuffix() {
        new SampleController().get();
        assertThat(pairs()).containsEntry("log.layer", "controller").containsEntry("log.origin.function", "get")
                .containsEntry("log.package", "com.loresentry.content.logging");
        new SampleService().get();
        assertThat(pairs()).containsEntry("log.layer", "service");
        new SampleListener().receive();
        assertThat(pairs()).containsEntry("log.layer", "consumer");
        new SamplePublisher().publish();
        assertThat(pairs()).containsEntry("log.layer", "publisher");
        new SampleStore().read();
        assertThat(pairs()).containsEntry("log.layer", "repository");
    }

    @Test
    void lambdasReportTheEnclosingMethodAndClass() {
        new SampleRepository().read();
        assertThat(pairs()).containsEntry("log.origin.function", "read").containsEntry("log.layer", "repository");
        assertThat(events.list.getLast().getLoggerName()).endsWith("AppLoggerLocationTest$SampleRepository");
    }

    @Test
    void scheduledJobsAreNamedByAnnotationAndUnknownClassesAreOther() {
        new SamplePurge().run();
        assertThat(pairs()).containsEntry("log.layer", "scheduler");
        new Helper().run();
        assertThat(pairs()).containsEntry("log.layer", "other");
    }

    @Test
    void contextTagsBecomeMarkersAndTheBindingIsRestored() {
        try (var bound = AppLogger.bind(new AppLogger("a".repeat(32), "b".repeat(16), null).withTag("health"))) {
            new SampleService().get();
            assertThat(AppLogger.current().traceId()).isEqualTo("a".repeat(32));
        }
        assertThat(events.list.getLast().getMarkerList()).extracting(org.slf4j.Marker::getName).containsExactly("health");
        assertThat(AppLogger.current().traceId()).isNull();
    }

    @Test
    void contextFieldsStayInMdcWhileBoundSoFrameworkLinesGetThem() {
        // 바인딩한 동안 같은 스레드에서 프레임워크가 찍는 줄(여기서는 SLF4J logger를 직접 쓴 줄)의 MDC에 흐름의 문맥 필드가 있다.
        // 예: Kafka 레코드 하나를 처리하는 listener, 스케줄 회차 하나
        org.slf4j.Logger framework = LoggerFactory.getLogger("org.springframework.sample.Framework");
        java.util.UUID event = java.util.UUID.randomUUID();
        AppLogger record = new AppLogger("a".repeat(32), "b".repeat(16), null).with(LogField.kafkaTopic("auth.user.deleted.v1"),
                LogField.kafkaPartition(3), LogField.kafkaOffset(42L), LogField.cloudeventId(event),
                LogField.scheduleName("outbox-relay"), LogField.projectId(java.util.UUID.randomUUID()));
        try (var bound = AppLogger.bind(record)) {
            framework.warn("framework line");
            assertThat(events.list.getLast().getMDCPropertyMap()).containsEntry("loresentry.kafka.topic", "auth.user.deleted.v1")
                    .containsEntry("loresentry.kafka.partition", "3").containsEntry("loresentry.kafka.offset", "42")
                    .containsEntry("loresentry.cloudevent.id", event.toString())
                    .containsEntry("loresentry.schedule.name", "outbox-relay").containsEntry("trace.id", "a".repeat(32))
                    // MDC_CONTEXT_KEYS 밖의 문맥 필드는 MDC에 넣지 않는다(우리 줄에만 붙는다)
                    .doesNotContainKey("loresentry.project_id");
        }
        // 바인딩이 끝나면 MDC에서 빠진다(listener·스케줄 스레드를 다시 쓰는 다음 흐름에 남지 않는다)
        framework.warn("after record");
        assertThat(events.list.getLast().getMDCPropertyMap()).doesNotContainKeys("loresentry.kafka.topic",
                "loresentry.kafka.offset", "loresentry.cloudevent.id", "loresentry.schedule.name", "trace.id");
    }

    @Test
    void aRecordPutsItsEventFieldsOnEveryLineAndUnknownTypesBecomeOther() {
        // Kafka 레코드 하나의 줄에는 이벤트의 ID·종류·대상이 붙는다. 헤더는 보낸 쪽 값 그대로라 처리하는 종류 밖은 other,
        // UUID 가 아닌 ID·대상은 남기지 않는다
        java.util.UUID event = java.util.UUID.randomUUID();
        java.util.UUID subject = java.util.UUID.randomUUID();
        var known = new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>("auth.user.deleted.v1", 0, 7L, "k", "v");
        known.headers().add("ce_id", event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        known.headers().add("ce_type", "UserDeleted".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        known.headers().add("ce_subject", subject.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var bound = AppLogger.bind(TraceContext.forRecord(known, java.util.Set.of("UserDeleted")))) {
            new SampleListener().receive();
        }
        assertThat(pairs()).containsEntry("loresentry.cloudevent.id", event.toString())
                .containsEntry("loresentry.cloudevent.type", "UserDeleted")
                .containsEntry("loresentry.cloudevent.subject", subject.toString());

        var unknown = new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>("auth.user.deleted.v1", 0, 8L, "k", "v");
        unknown.headers().add("ce_type", "someone@example.com".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        unknown.headers().add("ce_subject", "not-a-uuid".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (var bound = AppLogger.bind(TraceContext.forRecord(unknown, java.util.Set.of("UserDeleted")))) {
            new SampleListener().receive();
        }
        assertThat(pairs()).containsEntry("loresentry.cloudevent.type", "other")
                .doesNotContainKeys("loresentry.cloudevent.subject", "loresentry.cloudevent.id");
    }

    @Test
    void aStoredTraceparentBecomesTheTraceOfTheLine() {
        // outbox 행·Kafka 헤더에 저장해 둔 traceparent로 원래 흐름의 trace를 잇는다(LOG_SCHEMA.md 7절)
        AppLogger stored = TraceContext.fromTraceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertThat(stored.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(TraceContext.fromTraceparent("not-a-traceparent").traceId()).isNull();
        assertThat(TraceContext.fromTraceparent(null).traceId()).isNull();
    }
}
