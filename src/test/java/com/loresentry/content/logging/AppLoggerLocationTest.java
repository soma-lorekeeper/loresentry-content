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
    void aStoredTraceparentBecomesTheTraceOfTheLine() {
        // outbox 행·Kafka 헤더에 저장해 둔 traceparent로 원래 흐름의 trace를 잇는다(LOG_SCHEMA.md 7절)
        AppLogger stored = TraceContext.fromTraceparent("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertThat(stored.traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(TraceContext.fromTraceparent("not-a-traceparent").traceId()).isNull();
        assertThat(TraceContext.fromTraceparent(null).traceId()).isNull();
    }
}
