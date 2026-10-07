package com.loresentry.content.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Kafka 까지 필요한 테스트의 부모. relay 는 outbox(PostgreSQL)를 읽어 Kafka 로 보내고, listener 는 Kafka 에서
 * 받아 inbox(PostgreSQL)에 적는다. Kafka 테스트는 언제나 DB 도 함께 쓰므로 {@link ApiTestSupport} 위에 얹는다.
 *
 * <p>Kafka 가 필요 없는 테스트는 이 클래스를 상속하지 않는다. 컨테이너는 이 클래스가 처음 쓰일 때만 뜬다.
 */
public abstract class KafkaTestSupport extends ApiTestSupport {

    /**
     * 운영 broker 와 같은 버전이다. topic 자동 생성도 운영처럼 끈다 — 켜 두면 topic 이름을 틀려도 새 topic 이
     * 생겨서 테스트가 통과해 버린다.
     */
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:4.3.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        kafka.start();
        createTopics();
    }

    /** 컨테이너의 접속 주소는 뜰 때마다 달라진다. 앱이 localhost:9092 대신 이 주소를 쓰게 한다. */
    @DynamicPropertySource
    static void kafkaBootstrap(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    protected KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    /**
     * topic 에서 이 key 의 레코드가 {@code count} 개 나올 때까지 읽어, topic 에 실린 순서대로 돌려준다.
     * 컨테이너를 모든 테스트가 같이 쓰므로 topic 에는 다른 테스트가 보낸 레코드도 남아 있다. 그래서 "이만큼만
     * 있어야 한다"가 아니라 자기 key 를 찾는다.
     *
     * <p>읽을 때마다 새 group 으로 처음부터 읽는다. group 을 다시 쓰면 앞 테스트가 읽은 위치에서 이어 읽게 되어,
     * 테스트가 도는 순서에 따라 결과가 달라진다.
     *
     * @param topic 읽을 topic
     * @param key 찾을 레코드의 key. 테스트마다 새 UUID 를 key 로 써서 서로 겹치지 않게 한다
     * @param count 기다릴 레코드 수
     * @return key 가 맞는 레코드 {@code count} 개
     * @throws AssertionError 20초 안에 {@code count} 개가 모이지 않았을 때
     */
    protected List<ConsumerRecord<String, String>> awaitRecords(String topic, String key, int count) {
        // 지금까지 찾은, key 가 맞는 레코드. key 가 같으면 같은 partition 이라 읽은 순서가 곧 실린 순서다.
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        // 새 group 이름으로 consumer 를 만든다. 두 번째 인자는 client id 뒤에 붙일 글자인데 필요 없어 null 이다.
        // try-with-resources 라 메서드를 나갈 때 consumer 가 닫힌다.
        try (Consumer<String, String> consumer =
                consumerFactory.createConsumer("test." + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic));
            // 20초 뒤를 마감으로 잡는다. nanoTime 은 흘러간 시간만 재므로 시스템 시계가 바뀌어도 영향받지 않는다.
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                // 최대 0.5초 기다려 새로 도착한 레코드를 받는다. 없으면 빈 묶음이 온다.
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    // 다른 테스트가 보낸 레코드는 건너뛴다.
                    if (key.equals(record.key())) {
                        found.add(record);
                        // 다 모였으면 남은 시간을 기다리지 않고 곧바로 돌려준다.
                        if (found.size() == count) {
                            return found;
                        }
                    }
                }
            }
        }
        // 마감까지 다 모이지 않았다. 몇 개까지 왔는지 알려 주며 테스트를 실패시킨다.
        throw new AssertionError("expected " + count + " record(s) with key " + key + " on " + topic
                + " but found " + found.size());
    }

    /**
     * 물려받은 정리는 projects 만 지운다. outbox 와 inbox 는 프로젝트와 외래 키로 묶여 있지 않아서, 앞 테스트가
     * 남긴 행이 그대로 남는다. 매 테스트 전에 따로 비운다.
     */
    @BeforeEach
    void clearOutboxAndInbox() {
        jdbcClient.sql("delete from outbox_events").update();
        jdbcClient.sql("delete from inbox_events").update();
    }

    /**
     * 테스트 broker 에 미리 만들어 둘 topic. 운영에서는 gitops 의 KafkaTopic 선언이 하는 일이다.
     * broker 가 한 대뿐이라 replica 는 1 이다.
     */
    private static List<NewTopic> topics() {
        NewTopic contentProjectChangedV1 = new NewTopic("content.project.changed.v1", 6, (short) 1)
                .configs(Map.of("max.message.bytes", "4194304"));
        NewTopic contentGraphRefreshRequestedV1 = new NewTopic("content.graph-refresh.requested.v1", 3, (short) 1);
        NewTopic graphRagGraphRefreshCompletedV1 = new NewTopic("graph-rag.graph-refresh.completed.v1", 3, (short) 1);
        NewTopic authUserDeletedV1 = new NewTopic("auth.user.deleted.v1", 3, (short) 1);
        NewTopic graphRagGraphRefreshCompletedV1ContentDlq =
                new NewTopic("graph-rag.graph-refresh.completed.v1.content.dlq", 3, (short) 1);
        NewTopic authUserDeletedV1ContentDlq = new NewTopic("auth.user.deleted.v1.content.dlq", 3, (short) 1);

        return List.of(contentProjectChangedV1,
                contentGraphRefreshRequestedV1,
                graphRagGraphRefreshCompletedV1,
                authUserDeletedV1,
                graphRagGraphRefreshCompletedV1ContentDlq,
                authUserDeletedV1ContentDlq);
    }

    private static void createTopics() {
        try (Admin admin = Admin.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(topics()).all().get();
        } catch (Exception failure) {
            throw new IllegalStateException("could not create the test topics", failure);
        }
    }
}
