package com.loresentry.content.inbox;

import com.loresentry.content.refresh.GraphRefreshService;
import com.loresentry.content.user.UserDeletedService;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka 에서 이벤트를 받아 처리할 Service 로 넘긴다. 어느 topic 이 어느 Service 로 가는지가 이 클래스 한 곳에 있다.
 * 업무 로직은 없다 — 받은 것을 검사하고, 한 번만 처리되도록 감싸서 넘길 뿐이다.
 *
 * <p>메시지 하나는 이렇게 흐른다.
 * <ol>
 *   <li>검사: {@link InboxMessages} 가 계약대로인지 보고 이벤트로 바꾼다. 트랜잭션 밖이다 — 거부될 메시지 때문에 DB
 *       connection 을 잡지 않는다.
 *   <li>처리: {@link InboxTransaction#runOnce} 가 트랜잭션을 열고, 처리 기록을 적은 뒤 Service 를 부른다. 이미 처리한
 *       이벤트면 Service 를 부르지 않는다.
 *   <li>offset commit: 이 메서드가 정상으로 끝나면 spring-kafka 가 한다. DB commit 이 먼저고 offset 이 나중이다. 그
 *       사이에 죽으면 메시지가 다시 오고, 처리 기록이 그것을 거른다.
 * </ol>
 *
 * <p>처리하지 않는 종류의 메시지는 건너뛰고 처리 기록도 남기지 않는다. 계약에 맞지 않는 메시지는
 * {@link InvalidEventException} 이 이 메서드 밖으로 나가 spring-kafka 의 error handler 가 다룬다.
 *
 * <p>두 listener 는 설정(kafka.consumer.enabled.*)으로 따로 켜고 끈다. UserDeleted 는 기본으로 켜져 있다 — 회원
 * 탈퇴가 content 에 들어오는 유일한 길이다. GraphRefreshCompleted 는 기본이 꺼짐이다. 꺼져 있어도 listener 는
 * 등록되고 시작만 하지 않는다.
 *
 * <p>listener id, consumer group id, 처리 기록의 consumer_name 은 모두 kafka.consumer.groups.* 의 같은 값이다.
 * group id 는 적지 않아도 spring-kafka 가 listener id 를 쓰지만, 셋이 같다는 것을 드러내려고 직접 적는다.
 *
 * <p>받는 topic 도 설정(kafka.consumer.topics.*)에서 읽는다. topic 은 보내는 서비스와 정한 계약의 일부라,
 * {@link InboxMessages} 가 검사하는 ce_source 와 payload 모양과 함께 맞아야 한다.
 */
@Component
class InboxListener {

    /** 처리 기록과 처리를 한 트랜잭션으로 묶는 곳. */
    private final InboxTransaction inbox;

    /** UserDeleted 를 처리하는 곳. */
    private final UserDeletedService userDeleted;

    /** GraphRefreshCompleted 를 처리하는 곳({@link GraphRefreshService#complete}). */
    private final GraphRefreshService graphRefresh;

    /** UserDeleted 의 처리 기록에 적는 처리기 이름. 이 listener 의 group id 와 같은 값이다. */
    private final String userDeletedConsumer;

    /** GraphRefreshCompleted 의 처리 기록에 적는 처리기 이름. 이 listener 의 group id 와 같은 값이다. */
    private final String graphRefreshCompletedConsumer;

    /** 생성자가 하나뿐이라 Spring 이 bean 과 설정값을 찾아 넣어 준다. */
    InboxListener(InboxTransaction inbox,
                  UserDeletedService userDeleted,
                  GraphRefreshService graphRefresh,
                  @Value("${kafka.consumer.groups.user-deleted}") String userDeletedConsumer,
                  @Value("${kafka.consumer.groups.graph-refresh-completed}") String graphRefreshCompletedConsumer) {
        this.inbox = inbox;
        this.userDeleted = userDeleted;
        this.graphRefresh = graphRefresh;
        this.userDeletedConsumer = userDeletedConsumer;
        this.graphRefreshCompletedConsumer = graphRefreshCompletedConsumer;
    }

    /** kafka.consumer.topics.user-deleted({@code auth.user.deleted.v1})에서 받은 메시지 하나를 처리한다. */
    @KafkaListener(id = "${kafka.consumer.groups.user-deleted}",
            groupId = "${kafka.consumer.groups.user-deleted}",
            topics = "${kafka.consumer.topics.user-deleted}",
            autoStartup = "${kafka.consumer.enabled.user-deleted}")
    void receiveUserDeleted(ConsumerRecord<String, String> record) {
        // 처리하지 않는 종류면 빈 값이라 아무것도 하지 않는다. 계약에 맞지 않으면 여기서 예외가 나간다.
        InboxMessages.userDeleted(record).ifPresent(received ->
                // Service 를 부르는 일을 넘긴다. runOnce 가 트랜잭션을 연 채로 실행한다.
                inbox.runOnce(userDeletedConsumer, received.eventId(),
                        () -> userDeleted.handle(received.event())));
    }

    /**
     * kafka.consumer.topics.graph-refresh-completed({@code graph-rag.graph-refresh.completed.v1})에서 받은 메시지
     * 하나를 처리한다.
     */
    @KafkaListener(id = "${kafka.consumer.groups.graph-refresh-completed}",
            groupId = "${kafka.consumer.groups.graph-refresh-completed}",
            topics = "${kafka.consumer.topics.graph-refresh-completed}",
            autoStartup = "${kafka.consumer.enabled.graph-refresh-completed}")
    void receiveGraphRefreshCompleted(ConsumerRecord<String, String> record) {
        // 처리하지 않는 종류면 빈 값이라 아무것도 하지 않는다. 계약에 맞지 않으면 여기서 예외가 나간다.
        InboxMessages.graphRefreshCompleted(record).ifPresent(received ->
                // Service 를 부르는 일을 넘긴다. runOnce 가 트랜잭션을 연 채로 실행한다.
                inbox.runOnce(graphRefreshCompletedConsumer, received.eventId(),
                        () -> graphRefresh.complete(received.event())));
    }
}
