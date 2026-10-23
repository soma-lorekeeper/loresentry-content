package com.loresentry.content.outbox;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * outbox 에 쓸 이벤트 하나. {@link OutboxRepository} 는 이 값들이 무슨 뜻인지 모른다 — 어느 topic 으로 갈지는
 * {@link OutboxMessages#topicOf} 가 {@code eventType} 으로 정하고, payload 의 모양은 메시지를 만든 쪽만 안다.
 *
 * <p>payload 를 값이 아니라 <b>만드는 함수</b>로 받는다. {@link OutboxRepository#append} 가 {@code aggregateId} 의 lock 을
 * 잡은 뒤에 부르게 하려는 것이다. 미리 만든 값을 받으면 lock 전에 읽은 낡은 상태가 더 큰 seq 로 커밋될 수 있다.
 *
 * @param eventType   header {@code ce_type}. topic 도 이것으로 정해진다
 * @param aggregateId header {@code ce_subject}. 같은 값을 가진 이벤트끼리 순서를 지키는 단위이자 lock 단위다
 * @param key         Kafka key
 * @param payload     value 로 보낼 객체를 만든다. lock 을 잡은 뒤에 불린다. {@code null} 을 돌려주면 이 메시지는
 *                    쓰지 않는다 — 만들 시점에 대상이 이미 사라진 경우다
 */
public record OutboxRecord(String eventType, UUID aggregateId, String key, Supplier<Object> payload) {
}
