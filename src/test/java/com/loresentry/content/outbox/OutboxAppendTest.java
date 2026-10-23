package com.loresentry.content.outbox;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link OutboxRepository#append} 가 이벤트 내용을 모른 채 지키는 약속을 확인한다: 받은 그대로 쓰고, 같은 aggregate 의
 * payload 는 앞선 트랜잭션이 커밋한 <b>뒤에</b> 만든다. outbox_events 는 테스트 사이에 지워지지 않으므로 매번
 * 새 aggregate id 로 거른다.
 */
class OutboxAppendTest extends ApiTestSupport {

    @Autowired
    OutboxRepository outbox;

    @Autowired
    TransactionTemplate transaction;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    /** 아무 모양이나 직렬화되는지 보려는 payload. */
    record Sample(String name) {
    }

    @AfterEach
    void stopThreads() {
        pool.shutdownNow();
    }

    @Test
    void writesTheRecordAsGiven() throws Exception {
        UUID aggregate = UUID.randomUUID();

        transaction.executeWithoutResult(status -> outbox.append(new OutboxRecord(
                "FileChanged", aggregate, "project-key", () -> new Sample("첫 이벤트"))));

        record Row(String eventType, String eventKey, String payload) {
        }
        Row row = jdbcClient
                .sql("select event_type, event_key, payload::text as payload from outbox_events"
                        + " where aggregate_id = :id")
                .param("id", aggregate)
                .query((rows, index) -> new Row(rows.getString("event_type"), rows.getString("event_key"),
                        rows.getString("payload")))
                .single();
        assertThat(row.eventType()).isEqualTo("FileChanged");
        assertThat(row.eventKey()).isEqualTo("project-key");
        assertThat(json.readTree(row.payload())).isEqualTo(json.readTree("{\"name\":\"첫 이벤트\"}"));
    }

    @Test
    void skipsAMessageWhoseTargetIsAlreadyGone() {
        UUID aggregate = UUID.randomUUID();

        transaction.executeWithoutResult(status -> outbox.append(new OutboxRecord(
                "FileChanged", aggregate, "project-key", () -> null)));

        assertThat(rowsOf(aggregate)).isZero();
    }

    /** 따로 커밋되면 변경 없이 이벤트만 나가거나 그 반대가 된다. 트랜잭션 없이는 아예 실행하지 않는다. */
    @Test
    void refusesToRunOutsideATransaction() {
        UUID aggregate = UUID.randomUUID();

        assertThatThrownBy(() -> outbox.append(new OutboxRecord(
                "FileChanged", aggregate, "project-key", () -> new Sample("x"))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rowsOf(aggregate)).isZero();
    }

    /**
     * 순서 보장의 핵심. 첫 트랜잭션이 lock 을 쥔 채 payload 를 만드는 동안, 같은 aggregate 의 두 번째 트랜잭션은
     * lock 앞에서 기다린다. 그래서 두 번째 payload 는 첫 행이 커밋된 것을 보고 만들어지고, seq 도 그 뒤에 받는다.
     */
    @Test
    void buildsThePayloadOnlyAfterTheEarlierWriterOfTheSameAggregateCommits() throws Exception {
        UUID aggregate = UUID.randomUUID();
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> first = appendInBackground(aggregate, () -> {
            building.countDown();
            awaitQuietly(release);
            return new Sample("first");
        });
        assertThat(building.await(10, SECONDS)).isTrue();

        AtomicLong seenBySecond = new AtomicLong(-1);
        Future<?> second = appendInBackground(aggregate, () -> {
            seenBySecond.set(rowsOf(aggregate));
            return new Sample("second");
        });
        waitUntilSomeoneWaitsForAnAdvisoryLock();

        release.countDown();
        first.get(10, SECONDS);
        second.get(10, SECONDS);

        assertThat(seenBySecond.get()).isEqualTo(1);
        List<String> names = jdbcClient
                .sql("select payload->>'name' from outbox_events where aggregate_id = :id order by seq")
                .param("id", aggregate)
                .query(String.class)
                .list();
        assertThat(names).containsExactly("first", "second");
    }

    /** lock 단위는 aggregate 하나다. 다른 문서를 쓰는 트랜잭션은 기다리지 않는다. */
    @Test
    void doesNotMakeAnotherAggregateWait() throws Exception {
        UUID busy = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> first = appendInBackground(busy, () -> {
            building.countDown();
            awaitQuietly(release);
            return new Sample("busy");
        });
        assertThat(building.await(10, SECONDS)).isTrue();

        // 첫 트랜잭션이 아직 lock 을 쥐고 있는데도 끝난다.
        appendInBackground(other, () -> new Sample("other")).get(10, SECONDS);
        assertThat(rowsOf(other)).isEqualTo(1);

        release.countDown();
        first.get(10, SECONDS);
    }

    private Future<?> appendInBackground(UUID aggregate, Supplier<Object> payload) {
        return pool.submit(() -> transaction.executeWithoutResult(status ->
                outbox.append(new OutboxRecord("FileChanged", aggregate, "project-key", payload))));
    }

    private long rowsOf(UUID aggregate) {
        return jdbcClient.sql("select count(*) from outbox_events where aggregate_id = :id")
                .param("id", aggregate)
                .query(Long.class)
                .single();
    }

    /** 잠깐 쉬는 대신 DB 가 대기를 기록할 때까지 본다. 시간에 기대면 느린 CI 에서 흔들린다. */
    private void waitUntilSomeoneWaitsForAnAdvisoryLock() throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            long waiting = jdbcClient
                    .sql("select count(*) from pg_locks where locktype = 'advisory' and not granted")
                    .query(Long.class)
                    .single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("두 번째 트랜잭션이 advisory lock 을 기다리지 않았다");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, SECONDS)) {
                throw new AssertionError("풀어 주기를 기다리다 시간이 지났다");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
