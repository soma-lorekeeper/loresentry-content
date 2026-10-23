package com.loresentry.content.refresh.completion;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent.Outcome;
import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GraphRefreshCompleted 의 처리({@link GraphRefreshCompletionService#complete})는 부르는 쪽이 연 트랜잭션 안에서만 돈다. 처리
 * 기록 없이 처리만 되는 길이 막혀 있는지 확인한다. 여기서는 트랜잭션 규칙만 본다 — 반영 내용은
 * {@code GraphRefreshCompletionTest}, 요청 쪽은 {@code GraphRefreshRequestApiTest} 가 본다.
 */
class GraphRefreshCompletionServiceTest extends ApiTestSupport {

    /** 테스트 대상. Spring 이 만든 것이라 트랜잭션 규칙을 지키는 대리 객체를 거쳐 불린다. */
    @Autowired
    GraphRefreshCompletionService service;

    /** 코드로 트랜잭션을 여는 도구. inbox 의 InboxTransaction 이 하는 일을 대신한다. */
    @Autowired
    TransactionTemplate transaction;

    /** 실패한 최신화. 결과 위치 없이 끝난 경우라 result 와 error 를 비워도 처리 규칙을 보기에 충분하다. */
    private static GraphRefreshCompletedEvent event() {
        return new GraphRefreshCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), Outcome.FAILED, null, null, "v1");
    }

    @Test
    void refusesToRunWithoutATransaction() {
        assertThatThrownBy(() -> service.complete(event(), new ResultFetch.NoResult()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void runsInsideTheCallersTransaction() {
        assertThatCode(() -> transaction.executeWithoutResult(
                status -> service.complete(event(), new ResultFetch.NoResult())))
                .doesNotThrowAnyException();
    }
}
