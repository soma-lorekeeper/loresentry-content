package com.loresentry.content.logging;

import java.time.InstantSource;
import io.opentelemetry.api.metrics.Meter;

/**
 * 다른 패키지의 테스트가 시험용 SDK의 meter로 지표 객체를 만들 때 쓴다. 운영 코드는 {@link OutboxMetrics#register}로 agent의 meter에만
 * 등록하므로, 시험용 meter를 받는 생성자는 이 패키지 안에 두고 테스트에만 이 입구를 연다.
 */
public final class TestMeters {
    private TestMeters() {
    }

    /**
     * 그 meter에 gauge를 등록한 outbox 밀림 지표.
     *
     * @param meter 시험용 SDK의 meter
     * @param clock 나이를 잴 시계
     */
    public static OutboxMetrics outbox(Meter meter, InstantSource clock) {
        return new OutboxMetrics(meter, clock);
    }
}
