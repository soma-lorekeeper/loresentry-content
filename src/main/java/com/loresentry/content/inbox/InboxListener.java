package com.loresentry.content.inbox;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.logging.TraceContext;
import com.loresentry.content.refresh.completion.GraphRefreshCompletionService;
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
 * <p>두 listener 는 설정(kafka.consumer.enabled.*)으로 따로 켜고 끈다. 둘 다 기본으로 켜져 있다 — UserDeleted 는
 * 회원 탈퇴가 content 에 들어오는 유일한 길이고, GraphRefreshCompleted 가 꺼지면 최신화가 결과를 받지 못해 시간
 * 제한까지 문서가 잠겨 있다. 꺼져 있어도 listener 는 등록되고 시작만 하지 않는다.
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

    /** GraphRefreshCompleted 를 처리하는 곳({@link GraphRefreshCompletionService#complete}). */
    private final GraphRefreshCompletionService graphRefresh;

    /** UserDeleted 의 처리 기록에 적는 처리기 이름. 이 listener 의 group id 와 같은 값이다. */
    private final String userDeletedConsumer;

    /** GraphRefreshCompleted 의 처리 기록에 적는 처리기 이름. 이 listener 의 group id 와 같은 값이다. */
    private final String graphRefreshCompletedConsumer;

    /** 생성자가 하나뿐이라 Spring 이 bean 과 설정값을 찾아 넣어 준다. */
    InboxListener(InboxTransaction inbox,
                  UserDeletedService userDeleted,
                  GraphRefreshCompletionService graphRefresh,
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
        // 레코드 하나의 시작점. listener observation 이 연 span(보낸 쪽 traceparent 를 이은 것)으로 로그 객체를 만들어,
        // 이 레코드를 처리하는 모든 줄에 같은 trace 와 topic·partition·offset·이벤트 ID 가 붙게 한다.
        try (var bound = AppLogger.bind(TraceContext.forRecord(record))) {
            // 시작 줄. 시작할 때 아는 값(topic·partition·offset·이벤트 ID)은 바인딩한 로그 객체가 붙인다
            AppLogger.current().info("user deleted event received");
            long started = System.nanoTime();
            // 진행 중에 알게 되는 값. 종료 줄에 붙인다
            UUID deletedUser = null;
            Outcome outcome = Outcome.SKIPPED;
            try {
                // 처리하지 않는 종류면 빈 값이라 아무것도 하지 않는다. 계약에 맞지 않으면 여기서 예외가 나간다.
                var received = InboxMessages.userDeleted(record);
                if (received.isPresent()) {
                    deletedUser = received.get().event().userId();
                    // Service 를 부르는 일을 넘긴다. runOnce 가 트랜잭션을 연 채로 실행한다. 이미 처리한 이벤트면 false 다.
                    boolean processed = inbox.runOnce(userDeletedConsumer, received.get().eventId(),
                            () -> userDeleted.handle(received.get().event()));
                    outcome = processed ? Outcome.PROCESSED : Outcome.DUPLICATE;
                }
            } catch (RuntimeException failure) {
                finished(AppLogger.current().withUser(deletedUser), "user deleted event processed", Outcome.FAILED, started,
                        failure);
                throw failure;
            }
            // 삭제된 사용자는 이 이벤트의 대상이라 user.id 로 남긴다
            finished(AppLogger.current().withUser(deletedUser), "user deleted event processed", outcome, started, null);
        }
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
        // 레코드 하나의 시작점. receiveUserDeleted 와 같다.
        try (var bound = AppLogger.bind(TraceContext.forRecord(record))) {
            // 시작 줄. 시작할 때 아는 값(topic·partition·offset·이벤트 ID)은 바인딩한 로그 객체가 붙인다
            AppLogger.current().info("graph refresh completed event received");
            long started = System.nanoTime();
            // 진행 중에 알게 되는 값(최신화 요청과 그 프로젝트). 종료 줄에 붙인다
            UUID projectId = null;
            UUID requestId = null;
            Outcome outcome = Outcome.SKIPPED;
            try {
                // 처리하지 않는 종류면 빈 값이라 아무것도 하지 않는다. 계약에 맞지 않으면 여기서 예외가 나간다.
                var received = InboxMessages.graphRefreshCompleted(record);
                if (received.isPresent()) {
                    projectId = received.get().event().projectId();
                    requestId = received.get().event().requestId();
                    // 결과 파일(S3)은 트랜잭션을 열기 전에 읽는다. 읽는 동안 DB connection 을 잡지 않으려는 것이다. S3 일시
                    // 장애면 여기서 예외가 나가 다시 받는다. 이미 끝난 run 이면 S3 를 읽지 않는다.
                    var fetched = graphRefresh.fetchResult(received.get().event());
                    // Service 를 부르는 일을 넘긴다. runOnce 가 트랜잭션을 연 채로 실행한다. 이미 처리한 이벤트면 false 다.
                    boolean processed = inbox.runOnce(graphRefreshCompletedConsumer, received.get().eventId(),
                            () -> graphRefresh.complete(received.get().event(), fetched));
                    outcome = processed ? Outcome.PROCESSED : Outcome.DUPLICATE;
                }
            } catch (RuntimeException failure) {
                finished(AppLogger.current(), "graph refresh completed event processed", Outcome.FAILED, started, failure,
                        LogField.projectId(projectId), LogField.refreshRequestId(requestId));
                throw failure;
            }
            finished(AppLogger.current(), "graph refresh completed event processed", outcome, started, null,
                    LogField.projectId(projectId), LogField.refreshRequestId(requestId));
        }
    }

    /** 레코드 하나의 처리 결과. 종료 줄의 result.outcome 이다. */
    enum Outcome {
        /** 처음 온 이벤트라 처리했다. */
        PROCESSED,
        /** 이미 처리한 이벤트가 다시 와서 건너뛰었다(inbox 처리 기록). */
        DUPLICATE,
        /** 이 listener 가 처리하지 않는 종류라 건너뛰었다. */
        SKIPPED,
        /** 처리 중 예외가 났다. 에러 처리기가 다시 시도하거나 DLQ 로 보낸다. */
        FAILED
    }

    /**
     * 레코드 하나의 종료 줄. HTTP 요청의 request completed 처럼 결과(outcome)와 걸린 시간을 남긴다. 실패면 warn 이고 예외 종류를
     * error.type 에 붙인다. 실패의 원인 줄(재시도·DLQ)은 에러 처리기(InboxErrorHandling)가 남긴다.
     *
     * @param logger 진행 중에 알게 된 사용자를 더한 로그 객체(없으면 지금 바인딩된 것)
     * @param fields 진행 중에 알게 된 도메인 ID
     */
    private static void finished(AppLogger logger, String message, Outcome outcome, long started, RuntimeException failure,
                                 LogField... fields) {
        LogField[] all = Arrays.copyOf(fields, fields.length + 3);
        all[fields.length] = LogField.result("outcome", outcome);
        all[fields.length + 1] = LogField.duration(Duration.ofNanos(System.nanoTime() - started));
        all[fields.length + 2] = failure == null ? null : LogField.errorType(failure.getClass().getName());
        if (failure == null) logger.info(message, all);
        else logger.warn(message, all);
    }
}
