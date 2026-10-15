package com.loresentry.content.inbox;

import java.util.UUID;

/**
 * 받아들인 이벤트 하나. 계약이 이벤트를 둘로 나눠 싣는 것을 그대로 옮겼다 — 이벤트에 대한 정보는 header 에,
 * 이벤트의 데이터는 value 에 있다.
 *
 * @param eventId header {@code ce_id}. 발행한 쪽 outbox 행의 id 이고, inbox 에서 중복을 가리는 키다.
 * @param event value(JSON)를 읽은 객체
 * @param <T> 이벤트 객체의 타입. 쓰는 곳에서 정한다(예: {@code Received<UserDeletedEvent>}).
 */
record Received<T>(UUID eventId, T event) {
}
