package com.loresentry.content.user;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 유저가 삭제됐다. authentication 서비스가 {@code auth.user.deleted.v1} 로 보낸다.
 *
 * <p>계약의 JSON 은 {@code {"user_id": "…"}} 하나다. 이메일이나 이름 같은 개인정보는 싣지 않는다.
 *
 * <p>이 이벤트로 할 일(탈퇴한 유저의 콘텐츠 삭제, {@link UserDataService})이 이 package 에 있어서 여기에 둔다.
 * Kafka 에서 받아 이 모양으로 옮기는 것은 inbox package 의 {@code InboxMessages} 이고, 이 package 는 Kafka 와
 * inbox 를 모른다.
 *
 * @param userId 삭제된 유저의 id. 항상 있어야 하고 {@code InboxMessages} 가 확인한다. JSON library 는 필드가 없어도
 *         예외 없이 null 을 넣기 때문에, 읽은 뒤에 따로 봐야 한다.
 */
public record UserDeletedEvent(@JsonProperty("user_id") UUID userId) {
}
