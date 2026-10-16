package com.loresentry.content.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * UserDeleted 이벤트를 받았을 때 할 일. authentication 서비스가 계정을 지운 뒤 보낸다.
 *
 * <p>Kafka 에서 받는 쪽(inbox package 의 listener)이 처리 기록과 같은 트랜잭션 안에서 부른다. 그래서
 * {@code MANDATORY} 다 — 이미 열린 트랜잭션에 합류하고, 트랜잭션 없이 불리면 실행하기 전에 예외를 던진다. 처리 기록
 * 없이 처리만 되는 길을 막는다.
 *
 * <p>여기서 하는 DB 쓰기는 같은 thread 에서, 새 트랜잭션을 열지 않고 해야 한다. 다른 thread 로 넘기거나
 * {@code REQUIRES_NEW} 로 새로 열면 그 쓰기는 처리 기록과 따로 확정된다. DB 밖의 일(S3 등)은 commit 뒤로 미룬다 —
 * rollback 되어 이벤트가 다시 오면 처음부터 다시 처리되기 때문이다.
 */
@Service
public class UserDeletedService {
    private final UserDataService data;
    public UserDeletedService(UserDataService data) { this.data=data; }

    /**
     * 유저 삭제 이벤트 하나를 처리한다.
     *
     * @param event 삭제된 유저
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(UserDeletedEvent event) {
        data.purge(event.userId());
    }
}
