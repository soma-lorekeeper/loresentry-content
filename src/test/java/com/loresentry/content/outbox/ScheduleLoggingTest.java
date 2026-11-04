package com.loresentry.content.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * 스케줄 작업 1회의 줄에 작업 이름(loresentry.schedule.name)이 붙는지 확인한다(LOG_SCHEMA.md 5절 스케줄·Kafka 시작·종료 줄).
 * 작업 클래스가 찍은 시작·종료 줄뿐 아니라, 그 작업이 부른 repository 줄에도 붙어야 작업 하나가 남긴 줄을 이 값으로 모아 볼 수 있다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ScheduleLoggingTest extends ApiTestSupport {

    @Autowired
    OutboxPurge purge;

    @Test
    void everyLineOfAScheduledRunCarriesTheScheduleName(CapturedOutput output) {
        purge.run();

        List<JsonNode> lines = output.getOut().lines().filter(line -> line.startsWith("{")).map(json::readTree)
                .filter(line -> "outbox-purge".equals(line.at("/loresentry/schedule/name").asString())).toList();
        assertThat(lines).extracting(line -> line.get("message").asString())
                .containsSubsequence("schedule run start", "outbox published events delete", "schedule run completed");
        JsonNode start = lines.stream().filter(line -> "schedule run start".equals(line.get("message").asString()))
                .findFirst().orElseThrow();
        assertThat(start.at("/loresentry/param/retention_days").asLong()).isEqualTo(OutboxPurge.RETENTION.toDays());
        assertThat(start.at("/log/layer").asString()).isEqualTo("scheduler");
        // 시작·종료 줄의 위치는 흐름을 연 작업 메서드다(LogFlow 자신이 아니다)
        JsonNode end = lines.stream().filter(line -> "schedule run completed".equals(line.get("message").asString()))
                .findFirst().orElseThrow();
        assertThat(end.at("/log/logger").asString()).isEqualTo(OutboxPurge.class.getName());
        assertThat(end.at("/log/origin/function").asString()).isEqualTo("run");
        JsonNode repository = lines.stream().filter(line -> "outbox published events delete".equals(line.get("message").asString()))
                .findFirst().orElseThrow();
        assertThat(repository.at("/log/layer").asString()).isEqualTo("repository");
    }
}
