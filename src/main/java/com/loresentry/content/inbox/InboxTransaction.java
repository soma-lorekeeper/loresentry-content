package com.loresentry.content.inbox;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 받은 이벤트의 처리 기록과 처리를 한 트랜잭션으로 묶는다. consumer 쪽 트랜잭션은 여기서 연다.
 *
 * <p>Kafka 는 같은 메시지를 다시 보낼 수 있다. 처리를 마치고 offset 을 commit 하기 전에 pod 가 죽으면 다음 consumer 가
 * 같은 위치부터 다시 읽는다. 그래서 처리할 때 inbox 에 "이 처리기가 이 이벤트를 처리했다"를 함께 적고, 다시 온
 * 이벤트는 그 기록을 보고 건너뛴다.
 *
 * <p>기록과 처리는 함께 확정되거나 함께 취소되어야 한다. 기록만 남으면 처리되지 않은 이벤트가 다시 와도 걸러지고,
 * 처리만 남으면 다시 온 이벤트가 한 번 더 처리된다. 처리가 예외를 던지면 그 예외가 이 메서드 밖으로 나가고, 기록과
 * 처리의 DB 변경이 모두 rollback 된다. 그 이벤트는 나중에 다시 와서 처음처럼 처리된다. 그래서 "한 번만"은 정확히는
 * "한 번 성공적으로 처리된 이벤트는 다시 실행하지 않는다"이다.
 *
 * <p>처리는 {@link Runnable} 로 받는다. 부르는 쪽(Kafka listener)이 "Service 의 메서드를 부르는 일"을 값으로
 * 넘기면, 여기서 트랜잭션을 연 채로 실행한다. 그 안의 DB 쓰기가 같은 트랜잭션에 드는 이유는 thread 다. Spring 은
 * 트랜잭션의 DB connection 을 지금 thread 에 묶어 두고, 같은 thread 에서 부른 repository 는 그 connection 을 쓴다.
 * 그러니 처리는 같은 thread 에서, 새 트랜잭션을 열지 않고({@code REQUIRES_NEW} 금지) 해야 한다. 다른 thread 로
 * 넘기거나 새 트랜잭션을 열면 그 쓰기는 여기서 벗어나 따로 확정된다.
 *
 * <p>{@code Runnable} 은 checked exception 을 던질 수 없다. Spring 은 기본 설정에서 checked exception 이 나면
 * commit 해 버리는데, 여기서는 그런 예외가 나올 수 없으므로 "예외가 나면 rollback"이 항상 지켜진다.
 *
 * <p>검사(받은 record 를 이벤트로 바꾸는 일)는 이 트랜잭션 밖, 부르는 쪽에서 미리 한다. 거부될 메시지 때문에 DB
 * connection 을 잡지 않게 하려는 것이다.
 */
@Component
class InboxTransaction {

    /** 로그를 남기는 도구. 다시 온 이벤트를 건너뛸 때 한 줄 남긴다. */
    private static final Logger log = LoggerFactory.getLogger(InboxTransaction.class);

    /** 처리 기록을 적는 곳. */
    private final InboxRepository repository;

    /** 생성자가 하나뿐이라 Spring 이 InboxRepository 를 찾아 넣어 준다. */
    InboxTransaction(InboxRepository repository) {
        this.repository = repository;
    }

    /**
     * 이벤트를 이 처리기 기준으로 한 번만 처리한다. 처음 온 이벤트면 기록을 적고 처리를 실행한다. 이미 기록이 있으면
     * 처리를 실행하지 않고 돌아간다.
     *
     * <p>{@code @Transactional} 이라 이 메서드가 끝날 때 commit 되고, 예외가 나가면 rollback 된다. 이 annotation 은
     * 다른 객체에서 부를 때만 동작한다. Spring 이 이 객체 앞에 끼워 둔 대리 객체(proxy)가 트랜잭션을 열기 때문에,
     * 같은 클래스 안에서 자기 메서드를 부르면 대리 객체를 거치지 않아 트랜잭션이 열리지 않는다.
     *
     * @param consumerName 처리기의 이름. consumer group id 와 같은 값이다. 같은 이벤트라도 처리기마다 따로 센다
     * @param eventId 이벤트의 id. header {@code ce_id}
     * @param work 처리. 이 트랜잭션 안에서, 이 메서드를 부른 thread 에서 실행된다
     */
    @Transactional
    void runOnce(String consumerName, UUID eventId, Runnable work) {
        // inbox write 먼저, 그 이후 비즈니스 로직 처리.
        if (repository.insertIfAbsent(consumerName, eventId)) {
            // 새로 적혔다: 처음 온 이벤트다. 기록과 같은 트랜잭션에서 처리한다.
            work.run();
        } else {
            // 이미 있었다: 이 처리기가 처리를 마친 이벤트가 다시 왔다. 처리하지 않고, 다시 왔다는 것만 남긴다.
            log.info("inbox event already processed: consumer={}, event={}", consumerName, eventId);
        }
    }
}
