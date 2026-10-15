package com.loresentry.content.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 처리 기록과 처리가 한 트랜잭션으로 묶이는지 실제 PostgreSQL 에서 확인한다. Kafka 는 쓰지 않는다 — 처리 자리에
 * Service 대신 람다를 넣어, 무엇이 몇 번 실행됐고 무엇이 DB 에 남았는지를 직접 본다.
 */
@ExtendWith(OutputCaptureExtension.class)
class InboxTransactionTest extends ApiTestSupport {

    /** 테스트에서 쓰는 처리기 이름. 운영의 이름과 겹치지 않게 test 를 붙였다. */
    private static final String CONSUMER = "test.inbox-transaction";

    /** 테스트 대상. Spring 이 만든 것이라 트랜잭션을 여는 대리 객체를 거쳐 불린다. */
    @Autowired
    InboxTransaction inbox;

    /** 테스트 사이에 DB 를 비우지 않으므로, 앞 테스트가 남긴 처리 기록을 지우고 시작한다. */
    @BeforeEach
    void clearInbox() {
        jdbcClient.sql("delete from inbox_events").update();
    }

    /** 이 처리기와 이벤트의 기록이 몇 개 있는지 센다. */
    private long recorded(String consumerName, UUID eventId) {
        return jdbcClient.sql("select count(*) from inbox_events where consumer_name = :consumer and event_id = :event")
                .param("consumer", consumerName)
                .param("event", eventId)
                .query(Long.class)
                .single();
    }

    @Test
    void runsTheWorkAndRecordsTheEventTheFirstTime() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        inbox.runOnce(CONSUMER, eventId, runs::incrementAndGet);

        assertThat(runs).hasValue(1);
        assertThat(recorded(CONSUMER, eventId)).isEqualTo(1);
    }

    /** 같은 이벤트가 다시 오면 처리를 실행하지 않고, 운영에서 다시 온 것을 알 수 있게 한 줄을 남긴다. */
    @Test
    void doesNotRunTheWorkAgainForTheSameEvent(CapturedOutput output) {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        inbox.runOnce(CONSUMER, eventId, runs::incrementAndGet);
        inbox.runOnce(CONSUMER, eventId, runs::incrementAndGet);

        assertThat(runs).hasValue(1);
        assertThat(recorded(CONSUMER, eventId)).isEqualTo(1);
        assertThat(output.getOut())
                .contains("inbox event already processed: consumer=" + CONSUMER + ", event=" + eventId);
    }

    /**
     * 처리가 DB 에 쓰고 나서 실패하면, 처리의 쓰기와 처리 기록이 함께 사라진다. 처리 자리의 람다가 쓴 행까지 사라지는
     * 것이 "콜백 안의 DB 쓰기가 같은 트랜잭션에 든다"는 증거다. 기록이 남지 않았으므로 같은 이벤트가 다시 오면 처음처럼
     * 처리된다.
     */
    @Test
    void rollsBackTheRecordAndTheWorkTogetherWhenTheWorkFails() {
        UUID eventId = UUID.randomUUID();
        // 처리가 쓰는 행을 알아보는 표시. 외래 키가 없는 표라 행 하나만 넣을 수 있다.
        String marker = "inbox-transaction-test " + UUID.randomUUID();

        assertThatThrownBy(() -> inbox.runOnce(CONSUMER, eventId, () -> {
            jdbcClient.sql("insert into withdrawal_survey (comment) values (:comment)")
                    .param("comment", marker)
                    .update();
            throw new IllegalStateException("the work failed");
        })).isInstanceOf(IllegalStateException.class);

        // 처리의 쓰기도, 처리 기록도 남지 않았다.
        assertThat(jdbcClient.sql("select count(*) from withdrawal_survey where comment = :comment")
                .param("comment", marker)
                .query(Long.class)
                .single()).isZero();
        assertThat(recorded(CONSUMER, eventId)).isZero();

        // 다시 온 같은 이벤트는 처음처럼 처리된다.
        AtomicInteger runs = new AtomicInteger();
        inbox.runOnce(CONSUMER, eventId, runs::incrementAndGet);
        assertThat(runs).hasValue(1);
    }

    /** 처리기마다 따로 센다. 한 처리기가 처리했다고 다른 처리기가 막히지 않는다. */
    @Test
    void runsOncePerConsumerForTheSameEvent() {
        UUID eventId = UUID.randomUUID();
        String otherConsumer = "test.inbox-transaction-other";
        AtomicInteger runs = new AtomicInteger();

        inbox.runOnce(CONSUMER, eventId, runs::incrementAndGet);
        inbox.runOnce(otherConsumer, eventId, runs::incrementAndGet);

        assertThat(runs).hasValue(2);
        assertThat(recorded(CONSUMER, eventId)).isEqualTo(1);
        assertThat(recorded(otherConsumer, eventId)).isEqualTo(1);
    }
}
