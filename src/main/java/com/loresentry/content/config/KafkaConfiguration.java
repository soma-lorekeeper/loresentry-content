package com.loresentry.content.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * Kafka listener 의 켜고 끄는 설정이 앱이 다시 시작될 때도 지켜지게 한다.
 *
 * <p>listener 는 설정(kafka.consumer.enabled.*)으로 켜고 끈다({@code @KafkaListener(autoStartup = …)}). 그런데
 * spring-kafka 의 registry 는 기본으로, 앱이 한 번 뜬 뒤 다시 시작될 때(context 의 start 나 restart) 이 값을 보지 않고
 * 모든 listener 를 시작한다. 꺼 둔 listener 가 그렇게 켜지면, 처리 로직이 없는 채로 받은 이벤트를 처리한 것으로
 * 기록해 버린다. 그래서 다시 시작될 때도 listener 마다의 값을 따르게 한다.
 *
 * <p>운영의 앱은 뜬 뒤 다시 시작하지 않는다. 테스트 framework 는 쓰지 않는 앱을 멈춰 두었다가 다시 쓸 때 재시작해서,
 * 이 설정이 없으면 꺼 둔 listener 가 테스트 도중에 켜진다.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfiguration {

    /** registry 는 spring-kafka 가 만든 것을 받아, 앱이 요청을 받기 전에 한 번 설정한다. */
    KafkaConfiguration(KafkaListenerEndpointRegistry registry) {
        // false: 다시 시작될 때도 listener 마다의 autoStartup 을 따른다. 기본값 true 는 앱이 뜬 뒤라면 모두 시작한다.
        registry.setAlwaysStartAfterRefresh(false);
    }
}
