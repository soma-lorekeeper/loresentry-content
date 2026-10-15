package com.loresentry.content.inbox;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * inbox_events 테이블에 "이 처리기가 이 이벤트를 처리했다"를 적는다. 같은 이벤트가 다시 왔을 때 이 기록을 보고
 * 처리를 건너뛴다.
 *
 * <p>전제: 기록은 처리와 한 트랜잭션 안에서 적어야 한다. 따로 확정되면, 기록만 남고 처리가 취소되었을 때 그 이벤트는
 * 다시 와도 걸러져 영영 처리되지 않는다. 트랜잭션은 부르는 쪽({@link InboxTransaction})이 연다.
 *
 * <p>이 전제를 코드가 지키도록 {@code MANDATORY} 를 붙였다. 이미 열려 있는 트랜잭션에 합류하고, 없으면 SQL 을
 * 실행하기 전에 예외를 던진다. outbox 의 repository 와 같은 방식이다.
 */
@Repository
class InboxRepository {

    /** SQL 을 실행하는 Spring 의 도구. 이 프로젝트는 JPA 없이 SQL 을 직접 쓴다. */
    private final JdbcClient jdbcClient;

    /** 생성자가 하나뿐이라 Spring 이 JdbcClient 를 찾아 넣어 준다. */
    InboxRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 처리 기록을 넣는다. 같은 기록이 이미 있으면 넣지 않는다.
     *
     * <p>"이미 있는가"를 먼저 읽고 넣지 않고, 넣기를 시도한 결과로 판단한다. 읽고 넣는 사이에 다른 consumer 가 같은
     * 기록을 넣을 수 있기 때문이다. 기본 키(consumer_name, event_id)가 겹치면 PostgreSQL 이 넣지 않고 넘어가는데
     * ({@code on conflict do nothing}), 이 판단은 DB 안에서 한 번에 일어나서 둘이 동시에 넣어도 하나만 들어간다.
     *
     * @param consumerName 처리기의 이름. consumer group id 와 같은 값이다
     * @param eventId 이벤트의 id. header {@code ce_id}
     * @return 새로 넣었으면 true, 이미 있어서 넣지 않았으면 false
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean insertIfAbsent(String consumerName, UUID eventId) {
        int inserted = jdbcClient.sql("""
                        insert into inbox_events (consumer_name, event_id)
                        values (:consumer, :event)
                        on conflict do nothing
                        """)
                .param("consumer", consumerName)
                .param("event", eventId)
                .update();
        // 넣은 행의 수. 겹쳐서 넘어갔으면 0 이다.
        return inserted == 1;
    }
}
