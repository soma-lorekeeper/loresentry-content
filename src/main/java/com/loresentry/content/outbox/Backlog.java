package com.loresentry.content.outbox;

import java.time.OffsetDateTime;

/**
 * 아직 보내지 않은 outbox 행의 양. {@link OutboxRepository#backlog()} 가 세어 돌려주고 {@link OutboxRelay} 가
 * 지표(OutboxMetrics)로 넘긴다.
 *
 * @param pending 보내지 않았고 격리되지도 않은 행의 수
 * @param oldest 그중 가장 먼저 들어온 행의 생성 시각. 행이 없으면 null
 */
record Backlog(long pending, OffsetDateTime oldest) {

    /** 테스트 생성자가 쓰는 빈 값. 밀린 행이 없다. */
    static final Backlog NONE = new Backlog(0, null);
}
