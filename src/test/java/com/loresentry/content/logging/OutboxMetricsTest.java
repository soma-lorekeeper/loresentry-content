package com.loresentry.content.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.junit.jupiter.api.Test;

/**
 * outbox 밀림 지표가 relay가 알려 준 값을 docs ALERTING.md 7절의 이름·단위로 내보내는지 확인한다. 시험용 SDK의 meter에 등록하고, 지표를 읽는
 * 순간의 시계로 나이를 재는지 본다(agent가 1분마다 읽는 것과 같다).
 */
class OutboxMetricsTest {
    /** 지표를 읽어 가는 시험용 reader. {@link #read()}가 부를 때마다 gauge 콜백이 돈다. */
    private final InMemoryMetricReader reader = InMemoryMetricReader.create();

    /** 가짜 시계가 돌려주는 지금. 테스트가 값을 바꾸면 시간이 흐른 것이 된다. */
    private Instant now = Instant.parse("2026-11-05T00:00:00Z");

    /** 시험용 SDK의 meter와 가짜 시계를 끼운 지표 객체. */
    private final OutboxMetrics metrics = new OutboxMetrics(
            SdkMeterProvider.builder().registerMetricReader(reader).build().get("test"), () -> now);

    /** 지금 내보내는 지표 이름별 값. gauge는 계열이 하나라 첫 점의 값만 본다. 정수 gauge(행 수)는 실수로 바꿔 함께 본다. */
    private Map<String, Double> read() {
        return reader.collectAllMetrics().stream().collect(Collectors.toMap(MetricData::getName, metric ->
                metric.getLongGaugeData().getPoints().isEmpty()
                        ? metric.getDoubleGaugeData().getPoints().iterator().next().getValue()
                        : (double) metric.getLongGaugeData().getPoints().iterator().next().getValue()));
    }

    /** 앱이 막 떠서 아직 아무것도 모르면 아무 지표도 내보내지 않는다(0으로 내보내면 "밀린 것 없음"으로 읽힌다). */
    @Test
    void sendsNothingBeforeTheRelayReports() {
        assertThat(read()).isEmpty();
    }

    /** 밀린 행 수와 가장 오래된 행의 나이, relay가 마지막으로 돈 뒤 지난 초를 내보낸다. 나이는 읽는 순간의 시계로 잰다. */
    @Test
    void reportsTheBacklogAndAgesMeasuredWhenRead() {
        metrics.relayRan();
        metrics.backlog(3, now.minus(Duration.ofSeconds(40)));

        now = now.plus(Duration.ofSeconds(20));

        assertThat(read()).containsEntry(OutboxMetrics.PENDING, 3.0).containsEntry(OutboxMetrics.OLDEST_AGE, 60.0)
                .containsEntry(OutboxMetrics.LAST_RUN_AGE, 20.0);
    }

    /** 단위는 행 수가 {@code {row}}, 두 나이가 초({@code s})다. 경보 규칙과 Kibana가 이 단위로 읽는다. */
    @Test
    void usesTheDocumentedUnits() {
        metrics.relayRan();
        metrics.backlog(1, now);

        Map<String, String> units = reader.collectAllMetrics().stream()
                .collect(Collectors.toMap(MetricData::getName, MetricData::getUnit));
        assertThat(units).containsEntry(OutboxMetrics.PENDING, "{row}").containsEntry(OutboxMetrics.OLDEST_AGE, "s")
                .containsEntry(OutboxMetrics.LAST_RUN_AGE, "s");
    }

    /** 보낼 행이 없으면 가장 오래된 행의 나이는 0이다. */
    @Test
    void reportsZeroAgeWhenNothingIsPending() {
        metrics.relayRan();
        metrics.backlog(0, null);

        assertThat(read()).containsEntry(OutboxMetrics.PENDING, 0.0).containsEntry(OutboxMetrics.OLDEST_AGE, 0.0);
    }

    /** DB 시각이 앱 시계보다 앞서 있어도 나이를 음수로 내보내지 않는다. */
    @Test
    void neverReportsANegativeAge() {
        metrics.backlog(1, now.plus(Duration.ofSeconds(5)));

        assertThat(read()).containsEntry(OutboxMetrics.OLDEST_AGE, 0.0);
    }
}
