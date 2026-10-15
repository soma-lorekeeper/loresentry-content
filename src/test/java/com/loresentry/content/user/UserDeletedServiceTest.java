package com.loresentry.content.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UserDeleted 의 처리는 부르는 쪽이 연 트랜잭션 안에서만 돈다. 처리 기록 없이 처리만 되는 길이 막혀 있는지 확인한다.
 * 처리 본문은 아직 비어 있어서, 여기서는 트랜잭션 규칙만 본다.
 */
class UserDeletedServiceTest extends ApiTestSupport {

    /** 테스트 대상. Spring 이 만든 것이라 트랜잭션 규칙을 지키는 대리 객체를 거쳐 불린다. */
    @Autowired
    UserDeletedService service;

    /** 코드로 트랜잭션을 여는 도구. inbox 의 InboxTransaction 이 하는 일을 대신한다. */
    @Autowired
    TransactionTemplate transaction;

    @Test
    void refusesToRunWithoutATransaction() {
        assertThatThrownBy(() -> service.handle(new UserDeletedEvent(UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void runsInsideTheCallersTransaction() {
        assertThatCode(() -> transaction.executeWithoutResult(
                status -> service.handle(new UserDeletedEvent(UUID.randomUUID()))))
                .doesNotThrowAnyException();
    }
}
