package com.loresentry.content.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * 기본 설정의 앱에서 listener 가 돌지 않는지 확인한다. 운영의 기본도, 테스트의 기본도 꺼짐이다 — Service 의 처리가
 * 비어 있는 동안 켜지면 받은 이벤트를 처리한 것으로 기록만 하고 아무것도 하지 않는다.
 *
 * <p>꺼져 있어도 listener 는 등록된다. 시작만 하지 않으므로 Kafka 에 붙지 않는다. 그래서 broker 없이 확인할 수 있다.
 */
class InboxListenerSwitchTest extends ApiTestSupport {

    /** 두 listener 의 id. 설정의 group id 와 같은 값이다. */
    private static final List<String> LISTENERS = List.of("content.user-deleted", "content.graph-refresh-completed");

    /** 앱 안의 listener 들을 id 로 찾는 곳. */
    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Test
    void registersBothListenersButStartsNeither() {
        for (String id : LISTENERS) {
            MessageListenerContainer container = registry.getListenerContainer(id);
            assertThat(container).isNotNull();
            assertThat(container.isRunning()).isFalse();
        }
    }

    /**
     * 앱이 뜬 뒤 다시 시작돼도 꺼진 채다. registry 는 기본으로 이때 모든 listener 를 시작하므로
     * {@code KafkaConfiguration} 이 그것을 막는다. 테스트 framework 가 쉬던 앱을 다시 쓸 때 하는 일을 직접 부른다.
     */
    @Test
    void keepsBothStoppedWhenTheAppStartsAgain() {
        registry.start();

        for (String id : LISTENERS) {
            assertThat(registry.getListenerContainer(id).isRunning()).isFalse();
        }
    }
}
