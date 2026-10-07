package com.loresentry.content.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import javax.sql.DataSource;

import com.loresentry.content.support.KafkaTestSupport;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * relay 의 한 회차를 실제 PostgreSQL 과 Kafka 로 돌려 본다. outbox 에 쓰는 쪽은 아직 없으므로, 그쪽이 넣을 행을
 * SQL 로 직접 넣는다. 스케줄러에 맡기지 않고 테스트가 한 회차를 직접 부른다 — 언제 돌았는지를 기다리며 맞출
 * 필요가 없다.
 *
 * <p>{@link OutputCaptureExtension} 은 테스트가 도는 동안 stdout 에 찍힌 로그를 모아 준다. 격리와 반복 실패를
 * 알리는 로그의 문구는 운영에서 조회와 알림의 기준이 되므로, 문구가 바뀌면 테스트가 알아채게 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
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

    /** 코드로 트랜잭션을 여는 도구. 테스트가 직접 만든 publisher 에는 @Transactional 이 적용되지 않아서 쓴다. */
    @Autowired
    TransactionTemplate transaction;

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

    /**
     * 행의 발행·실패 기록을 컬럼 이름으로 꺼낼 수 있게 돌려준다. 키는 attempts, last_error, quarantined_at,
     * published_at 이고, 값이 없는 컬럼은 null 이다.
     */
    private Map<String, Object> row(UUID id) {
        return jdbcClient
                .sql("select attempts, last_error, quarantined_at, published_at from outbox_events where id = :id")
                .param("id", id)
                .query()
                .singleRow();
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
        assertThatThrownBy(() -> repository.markFailed(UUID.randomUUID(), "reason"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.quarantine(UUID.randomUUID(), "reason"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * 한도(4MiB)를 넘는 행은 몇 번을 보내도 거절된다. 그대로 두면 매 회차가 이 행에서 멈춰 뒤의 행이 영영 나가지
     * 못하므로, 격리해서 줄에서 빼고 뒤의 행은 같은 회차에 그대로 보낸다.
     */
    @Test
    void quarantinesARowTooLargeToSendAndGoesOn(CapturedOutput output) {
        String projectId = UUID.randomUUID().toString();
        // 압축하면 아주 작아지지만 한도는 압축 전 크기로 잰다. send() 가 그 자리에서 거절한다.
        String body = "a".repeat(4 * 1024 * 1024 + 1);
        UUID tooLarge = insert("FileChanged", projectId, UUID.randomUUID(), "{\"body\":\"" + body + "\"}");
        UUID next = insertFileChanged(projectId);

        // 격리는 다시 보낼 실패가 아니므로 곧바로 이어서 돌아도 된다.
        assertThat(publisher.publishBatch()).isTrue();

        Map<String, Object> row = row(tooLarge);
        assertThat(row.get("quarantined_at")).isNotNull();
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("RecordTooLargeException");
        // 뒤의 행은 막히지 않고 나갔다.
        assertThat(publishedAt(next)).isNotNull();
        // 운영에서 이 문구로 격리를 찾는다.
        assertThat(output.getOut()).contains("outbox event quarantined: id=" + tooLarge + ", type=FileChanged");
        // 격리된 행은 다음 회차가 다시 고르지 않는다.
        assertThat(publisher.publishBatch()).isFalse();
    }

    /** 계약에 없는 종류의 행은 어느 topic 으로 보낼지 정할 수 없다. 다시 읽어도 같으므로 격리한다. */
    @Test
    void quarantinesARowWithAnEventTypeTheContractDoesNotName() {
        String projectId = UUID.randomUUID().toString();
        UUID unknown = insert("Mystery", projectId, UUID.randomUUID(), "{}");
        UUID next = insertFileChanged(projectId);

        assertThat(publisher.publishBatch()).isTrue();

        Map<String, Object> row = row(unknown);
        assertThat(row.get("quarantined_at")).isNotNull();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("unknown event type: Mystery");
        assertThat(publishedAt(next)).isNotNull();
    }

    /**
     * producer 의 한도(4MiB)는 넘지 않지만 topic 의 한도는 넘는 행이다. graph-refresh topic 은 한도를 따로 정하지
     * 않아 기본값(약 1MB)이다. 이때는 send() 가 아니라 broker 의 응답으로 실패를 알게 되는데, 마찬가지로 격리한다.
     *
     * <p>잘 줄지 않는 글이어야 한다. topic 의 한도는 압축한 뒤의 크기로 재므로, 같은 글자의 반복은 통과해 버린다.
     */
    @Test
    void quarantinesARowTheBrokerRefuses() {
        String projectId = UUID.randomUUID().toString();
        // seed 가 같아 실행마다 같은 내용이다. 무작위 영문 3MB 는 압축해도 1MB 를 넘는다.
        String body = new Random(17).ints(3_000_000, 'a', 'z' + 1)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        UUID refused = insert("GraphRefreshRequested", projectId, UUID.randomUUID(), "{\"body\":\"" + body + "\"}");
        UUID next = insertFileChanged(projectId);

        assertThat(publisher.publishBatch()).isTrue();

        Map<String, Object> row = row(refused);
        assertThat(row.get("quarantined_at")).isNotNull();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("RecordTooLargeException");
        assertThat(publishedAt(next)).isNotNull();
    }

    /**
     * broker 에 닿지 못하는 것은 행의 문제가 아니다. 격리하지 않고 횟수와 이유만 남긴 채 그 행에서 멈춘다. 같은
     * 실패가 {@link OutboxPublisher#WARN_AFTER_ATTEMPTS} 번 이어지면 그때부터 로그로 알린다.
     */
    @Test
    void recordsTheAttemptAndWarnsWhenTheBrokerStaysUnreachable(CapturedOutput output) {
        String projectId = UUID.randomUUID().toString();
        UUID first = insertFileChanged(projectId);
        UUID second = insertFileChanged(projectId);
        // 아무도 듣지 않는 주소로 보내는 producer. send() 가 broker 정보를 0.5초 기다린 뒤 실패한다.
        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 500));
        // Spring 이 만든 bean 이 아니라서 @Transactional 이 적용되지 않는다. 회차마다 트랜잭션을 직접 연다.
        OutboxPublisher unreachable = new OutboxPublisher(repository, new KafkaTemplate<>(factory));
        try {
            for (int round = 1; round < OutboxPublisher.WARN_AFTER_ATTEMPTS; round++) {
                // 결과를 변수에 먼저 받는다. execute() 가 돌려주는 타입을 assertThat 안에서는 컴파일러가 정하지 못한다.
                Boolean goOn = transaction.execute(status -> unreachable.publishBatch());
                // 다시 보내야 할 실패가 있었으므로 false 다. 부르는 쪽은 곧바로 이어서 돌리지 않는다.
                assertThat(goOn).isFalse();
            }
            // 아직 임계값에 이르지 않았으므로 조용하다.
            assertThat(output.getOut()).doesNotContain("outbox events still failing");

            Boolean goOn = transaction.execute(status -> unreachable.publishBatch());
            assertThat(goOn).isFalse();
        } finally {
            // 닫지 않으면 producer 의 thread 가 남아 접속을 계속 시도한다.
            factory.destroy();
        }

        Map<String, Object> row = row(first);
        assertThat(row.get("attempts")).isEqualTo(OutboxPublisher.WARN_AFTER_ATTEMPTS);
        assertThat((String) row.get("last_error")).contains("TimeoutException");
        assertThat(row.get("quarantined_at")).isNull();
        assertThat(row.get("published_at")).isNull();
        // 첫 행에서 멈췄으므로 둘째 행은 시도하지 않았다. 시도하지 않은 행의 횟수는 올리지 않는다.
        assertThat(row(second).get("attempts")).isEqualTo(0);
        // 운영에서 이 문구로 반복 실패를 찾는다. 뒤에는 이유별 개수가 붙는다 — 시도한 행이 하나뿐이라 1 이다.
        assertThat(output.getOut()).contains(
                "outbox events still failing: {org.apache.kafka.common.errors.TimeoutException=1}");
    }
}
