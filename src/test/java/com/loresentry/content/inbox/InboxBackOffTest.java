package com.loresentry.content.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

/**
 * 운영에서 다시 시도하는 간격이 정한 대로인지 확인한다. Spring 없이 설정 클래스의 메서드를 직접 부른다 — 간격 계산은
 * 시계를 쓰지 않아 기다리지 않고 볼 수 있다.
 */
class InboxBackOffTest {

    @Test
    void waitsOneTwoFourEightAndTenSecondsThenGivesUp() {
        BackOffExecution execution = new InboxErrorHandling().inboxBackOff().start();

        // 다섯 번 다시 시도하고, 그 사이의 간격은 두 배씩 늘다가 10초에서 멈춘다(밀리초).
        assertThat(execution.nextBackOff()).isEqualTo(1_000);
        assertThat(execution.nextBackOff()).isEqualTo(2_000);
        assertThat(execution.nextBackOff()).isEqualTo(4_000);
        assertThat(execution.nextBackOff()).isEqualTo(8_000);
        assertThat(execution.nextBackOff()).isEqualTo(10_000);
        // 여섯 번째는 없다. 이때 DLQ 로 보낸다.
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }
}
