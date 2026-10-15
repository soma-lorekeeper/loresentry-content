package com.loresentry.content.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.support.KafkaUtils;

/**
 * spring-kafka 의 기본 동작 중 이 앱에 맞지 않는 두 가지를 바꾼다.
 *
 * <p>첫째, Kafka listener 의 켜고 끄는 설정이 앱이 다시 시작될 때도 지켜지게 한다. listener 는 설정
 * (kafka.consumer.enabled.*)으로 켜고 끈다({@code @KafkaListener(autoStartup = …)}). 그런데 spring-kafka 의 registry 는
 * 기본으로, 앱이 한 번 뜬 뒤 다시 시작될 때(context 의 start 나 restart) 이 값을 보지 않고 모든 listener 를 시작한다.
 * 꺼 둔 listener 가 그렇게 켜지면, 처리 로직이 없는 채로 받은 이벤트를 처리한 것으로 기록해 버린다. 그래서 다시 시작될
 * 때도 listener 마다의 값을 따르게 한다. 운영의 앱은 뜬 뒤 다시 시작하지 않는다. 테스트 framework 는 쓰지 않는 앱을
 * 멈춰 두었다가 다시 쓸 때 재시작해서, 이 설정이 없으면 꺼 둔 listener 가 테스트 도중에 켜진다.
 *
 * <p>둘째, 보내는 메시지를 로그에 옮길 때 내용(value)을 빼게 한다. spring-kafka 는 보내는 record 를 기본으로 통째로
 * 글자로 바꿔 찍는데, 거기에는 원고 본문 같은 payload 가 그대로 들어간다. 그렇게 찍는 곳은 DEBUG·TRACE 수준뿐이라
 * 평소에는 나가지 않는다. 장애를 보려고 그 수준을 잠시 켜도 본문이 로그에 남지 않게 한다.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfiguration {

    /** registry 는 spring-kafka 가 만든 것을 받아, 앱이 요청을 받기 전에 한 번 설정한다. */
    KafkaConfiguration(KafkaListenerEndpointRegistry registry) {
        // false: 다시 시작될 때도 listener 마다의 autoStartup 을 따른다. 기본값 true 는 앱이 뜬 뒤라면 모두 시작한다.
        registry.setAlwaysStartAfterRefresh(false);
        // 보내는 record 는 topic 과 partition 만 찍는다. 앱 전체에 한 번 정하는 값이다(static). partition 을 정하지 않고
        // 보낸 record 는 null 로 찍힌다.
        KafkaUtils.setProducerRecordFormatter(record -> record.topic() + "-" + record.partition());
    }
}
