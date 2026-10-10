package com.loresentry.content.inbox;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 유저가 삭제됐다. authentication 서비스가 {@code auth.user.deleted.v1} 로 보낸다.
 *
 * <p>계약의 JSON 은 {@code {"user_id": "…"}} 하나다. 이메일이나 이름 같은 개인정보는 싣지 않는다.
 *
 * @param userId 삭제된 유저의 id. 항상 있어야 하고 {@link InboxMessages} 가 확인한다. JSON library 는 필드가 없어도
 *         예외 없이 null 을 넣기 때문에, 읽은 뒤에 따로 봐야 한다.
 */
record UserDeletedEvent(@JsonProperty("user_id") UUID userId) {
}
