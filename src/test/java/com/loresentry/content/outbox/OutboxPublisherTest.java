package com.loresentry.content.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.loresentry.content.support.KafkaTestSupport;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * relay 의 한 회차를 실제 PostgreSQL 과 Kafka 로 돌려 본다. outbox 에 쓰는 쪽은 아직 없으므로, 그쪽이 넣을 행을
 * SQL 로 직접 넣는다. 스케줄러에 맡기지 않고 테스트가 한 회차를 직접 부른다 — 언제 돌았는지를 기다리며 맞출
 * 필요가 없다.
 */
class OutboxPublisherTest extends KafkaTestSupport {

    /**
     * FileChanged 가 실려야 하는 topic. main 의 상수를 가져다 쓰지 않고 따로 적는다 — 같은 상수를 쓰면 그 값이
     * 틀려도 테스트가 함께 틀려서 알아채지 못한다.
     */
    private static final String PROJECT_CHANGED = "content.project.changed.v1";

    /** 테스트 대상. 스케줄러 없이 한 회차를 직접 부른다. */
    @Autowired
    OutboxPublisher publisher;

    /** publisher 를 거치지 않고 직접 부를 때만 쓴다. 트랜잭션 밖에서 불렸을 때의 동작을 본다. */
    @Autowired
    OutboxRepository repository;

    /** 앱이 쓰는 것과 별개의 DB 연결을 얻는 데 쓴다. 다른 인스턴스를 흉내 낼 때 필요하다. */
    @Autowired
    DataSource dataSource;

    /**
     * outbox 에 미발행 행 하나를 넣는다. outbox 에 쓰는 쪽이 할 일을 대신한다. 적지 않은 컬럼(id, seq,
     * created_at 등)은 DB 가 채운다.
     *
     * @param eventType 이벤트 종류(예: FileChanged). 어느 topic 으로 갈지와 ce_type 을 정한다
     * @param projectId Kafka 레코드의 key 가 된다. key 가 같으면 같은 partition 으로 간다
     * @param aggregateId 이벤트가 가리키는 대상의 id. ce_subject 가 된다
     * @param payload 레코드의 value 가 될 JSON 글자
     * @return DB 가 만든 행의 id. ce_id 가 된다
     */
    private UUID insert(String eventType, String projectId, UUID aggregateId, String payload) {
        // :payload::jsonb 는 글자로 넘긴 JSON 을 jsonb 컬럼에 맞게 바꾼다. returning id 는 방금 넣은 행의 id 를
        // 돌려받는다.
        return jdbcClient
                .sql("""
                        insert into outbox_events (event_type, event_key, aggregate_id, payload)
                        values (:type, :key, :aggregate, :payload::jsonb)
                        returning id
                        """)
                .param("type", eventType)
                .param("key", projectId)
                .param("aggregate", aggregateId)
                .param("payload", payload)
                .query(UUID.class)
                .single();
    }

    /** 내용이 중요하지 않은 FileChanged 행 하나를 넣는다. 파일 id 는 부를 때마다 새로 만든다. */
    private UUID insertFileChanged(String projectId) {
        UUID fileId = UUID.randomUUID();
        return insert("FileChanged", projectId, fileId, "{\"file_id\":\"" + fileId + "\"}");
    }

    /**
     * 행의 published_at 을 읽는다. 아직 보내지 않은 행이면 null 이다. single() 은 null 인 결과를 받지 않으므로
     * optional() 로 받는다.
     */
    private OffsetDateTime publishedAt(UUID id) {
        return jdbcClient.sql("select published_at from outbox_events where id = :id")
                .param("id", id)
                .query((rows, index) -> rows.getObject("published_at", OffsetDateTime.class))
                .optional()
                .orElse(null);
    }

    /** 아직 보내지 않은 행의 수. 매 테스트 전에 outbox 를 비우므로 그 테스트가 넣은 행만 세게 된다. */
    private long pendingCount() {
        return jdbcClient.sql("select count(*) from outbox_events where published_at is null")
                .query(Long.class)
                .single();
    }

    /** 레코드의 header 값을 글자로 읽는다. header 는 바이트로 실려 오고, 같은 이름이 여럿이면 마지막 것을 본다. */
    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    /** 기본 경로다. 미발행 행 하나가 계약대로의 header 와 내용으로 Kafka 에 실리고, 그 행에 발행 표시가 남는다. */
    @Test
    void publishesAPendingRowAndMarksIt() throws Exception {
        String projectId = UUID.randomUUID().toString();
        UUID fileId = UUID.randomUUID();
        UUID id = insert("FileChanged", projectId, fileId, "{\"file_id\":\"" + fileId + "\"}");
        // seq 는 DB 가 매기므로 넣은 뒤에 읽어 온다. ce_sequence 와 비교할 값이다.
        long seq = jdbcClient.sql("select seq from outbox_events where id = :id")
                .param("id", id).query(Long.class).single();

        // 보낸 것이 있고 실패가 없었으니 true 다.
        assertThat(publisher.publishBatch()).isTrue();

        ConsumerRecord<String, String> record = awaitRecords(PROJECT_CHANGED, projectId, 1).getFirst();
        // header 가 outbox 의 어느 컬럼에서 왔는지 하나씩 확인한다.
        // ce_id ← id, ce_type ← event_type, ce_subject ← aggregate_id, ce_sequence ← seq
        assertThat(header(record, "ce_id")).isEqualTo(id.toString());
        assertThat(header(record, "ce_type")).isEqualTo("FileChanged");
        assertThat(header(record, "ce_subject")).isEqualTo(fileId.toString());
        assertThat(header(record, "ce_sequence")).isEqualTo(Long.toString(seq));
        // jsonb 는 공백과 키 순서를 자기 방식으로 정리해 돌려주므로 글자가 아니라 내용으로 비교한다.
        assertThat(json.readTree(record.value()).get("file_id").asString()).isEqualTo(fileId.toString());
        // 발행 표시가 남았다.
        assertThat(publishedAt(id)).isNotNull();
    }

    /** 한 번 보낸 행은 다시 고르지 않는다. 두 번째 회차는 보낼 것이 없고, 발행 시각도 처음 그대로다. */
    @Test
    void sendsNothingTwice() {
        String projectId = UUID.randomUUID().toString();
        UUID id = insertFileChanged(projectId);

        assertThat(publisher.publishBatch()).isTrue();
        OffsetDateTime firstPublishedAt = publishedAt(id);

        // 보낼 행이 없으면 false 다. relay 는 이것을 보고 다음 주기까지 쉰다.
        assertThat(publisher.publishBatch()).isFalse();
        // 다시 보냈다면 발행 시각이 새로 적혔을 것이다.
        assertThat(publishedAt(id)).isEqualTo(firstPublishedAt);
    }

    /** 계약의 핵심이다. 한 프로젝트 안의 변경은 일어난 순서대로 도착해야 한다. */
    @Test
    void keepsTheOrderOfChangesInsideOneProject() {
        String projectId = UUID.randomUUID().toString();
        // 같은 프로젝트(= 같은 key)로 세 번 넣는다. 넣은 순서가 곧 기대하는 도착 순서다.
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(insertFileChanged(projectId).toString());
        }

        publisher.publishBatch();

        List<ConsumerRecord<String, String>> records = awaitRecords(PROJECT_CHANGED, projectId, 3);
        // 도착한 순서가 넣은 순서와 같다.
        assertThat(records).extracting(record -> header(record, "ce_id")).containsExactlyElementsOf(ids);
        // ce_sequence 는 뒤로 갈수록 커지고 겹치지 않는다. 소비자는 이 값으로 지난 이벤트를 가려낸다.
        assertThat(records).extracting(record -> Long.parseLong(header(record, "ce_sequence")))
                .isSorted()
                .doesNotHaveDuplicates();
    }

    /** 행 수 상한보다 하나 많이 넣는다. 첫 회차는 상한만큼만 보내고, 남은 하나는 다음 회차가 보낸다. */
    @Test
    void stopsAtTheRowLimitAndLeavesTheRestForTheNextRound() {
        String projectId = UUID.randomUUID().toString();
        for (int i = 0; i < OutboxPublisher.MAX_ROWS + 1; i++) {
            insertFileChanged(projectId);
        }

        // 한 묶음을 보냈고 실패가 없었으니 곧바로 이어서 돌아도 된다.
        assertThat(publisher.publishBatch()).isTrue();
        assertThat(pendingCount()).isEqualTo(1);

        // 두 번째 회차가 남은 하나를 보낸다.
        assertThat(publisher.publishBatch()).isTrue();
        assertThat(pendingCount()).isZero();
    }

    /**
     * 3MB 짜리 행 일곱 개는 21MB 다. 여섯 번째를 담는 순간 합계가 16MB 를 넘으므로 거기서 멈추고, 일곱 번째는
     * 다음 회차로 넘긴다. 상한에 닿게 만든 행까지는 담는다 — 그래야 상한보다 큰 행 하나가 영영 못 나가는 일이 없다.
     */
    @Test
    void stopsAtTheByteLimitEvenWhenFewRowsAreRead() {
        String projectId = UUID.randomUUID().toString();
        // 약 3MB 인 payload 의 본문. 크기만 필요하고 내용은 중요하지 않다.
        String body = "a".repeat(3_000_000);
        for (int i = 0; i < 7; i++) {
            insert("FileChanged", projectId, UUID.randomUUID(), "{\"body\":\"" + body + "\"}");
        }

        assertThat(publisher.publishBatch()).isTrue();

        // 일곱 개 가운데 여섯 개가 나가고 하나가 남았다. 행 수 상한(50)에는 한참 못 미친다.
        assertThat(pendingCount()).isEqualTo(1);
    }

    /**
     * 배포 중에는 옛 pod 와 새 pod 가 잠시 함께 떠 있다. 둘이 같은 행을 나눠 보내면 한 프로젝트의 순서가 뒤집히므로,
     * 잠금을 못 얻은 쪽은 이번 회차를 건너뛴다.
     */
    @Test
    void skipsTheRoundWhileAnotherInstanceIsPublishing() throws Exception {
        UUID id = insertFileChanged(UUID.randomUUID().toString());

        // 다른 인스턴스를 흉내 낸다. 별도의 연결에서 트랜잭션을 열고 relay 잠금을 잡은 채로 둔다.
        try (Connection other = dataSource.getConnection()) {
            // 자동 commit 을 꺼야 아래 SQL 이 끝난 뒤에도 트랜잭션이 이어지고 잠금이 유지된다.
            other.setAutoCommit(false);
            try (Statement statement = other.createStatement()) {
                // 이름에 try 가 없는 쪽은 얻을 때까지 기다린다. 지금은 아무도 잡고 있지 않아 곧바로 얻는다.
                statement.execute("select pg_advisory_xact_lock(" + OutboxRepository.RELAY_LOCK_KEY + ")");
            }

            // 잠금을 못 얻었으므로 아무것도 보내지 않고 false 다.
            assertThat(publisher.publishBatch()).isFalse();
            assertThat(publishedAt(id)).isNull();

            // 트랜잭션을 끝내 잠금을 푼다.
            other.rollback();
        }

        // 잠금은 트랜잭션이 끝나면서 풀렸다. 이제는 이쪽이 보낸다.
        assertThat(publisher.publishBatch()).isTrue();
        assertThat(publishedAt(id)).isNotNull();
    }

    /**
     * 잠금은 트랜잭션이 끝날 때까지 유지되어야 뜻이 있다. 트랜잭션 없이 불리면 잠금이 곧바로 풀려 오류 없이 틀린
     * 동작을 하게 되므로, repository 는 그런 호출을 SQL 을 실행하기 전에 거절한다. 이 테스트는 트랜잭션을 열지
     * 않으므로 여기서 직접 부르는 것이 곧 "트랜잭션 밖에서 부르는 것"이다.
     */
    @Test
    void repositoryRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> repository.tryRelayLock())
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.lockPending(1, 1))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.markPublished(List.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
