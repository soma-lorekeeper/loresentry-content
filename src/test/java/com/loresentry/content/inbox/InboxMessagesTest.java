package com.loresentry.content.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent.ErrorDetail;
import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent.Outcome;
import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent.Result;
import com.loresentry.content.user.UserDeletedEvent;
import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 받은 record 가 계약대로 받아들여지고, 건너뛰어지고, 거부되는지 확인한다. Kafka 도 DB 도 Spring 도 쓰지 않는다 —
 * {@link ConsumerRecord} 는 평범한 객체라 손으로 만들어 header 를 붙일 수 있다.
 *
 * <p>테스트마다 계약에 맞는 record 에서 시작해 한 군데만 떼거나 바꾼다. 그래서 실패하면 그 한 군데가 원인이다.
 *
 * <p>거부 메시지는 글자 전체를 비교한다. 메시지는 DLQ 로 간 record 에 실려 사람이 읽는 것이고, 받은 값이 섞여
 * 들어가지 않았다는 것도 함께 확인된다.
 *
 * <p>{@link OutputCaptureExtension} 은 테스트가 도는 동안 찍힌 로그를 모아 준다. 건너뜀 로그의 문구는 운영에서
 * 로그를 찾는 기준이라, 바뀌면 테스트가 알아채게 한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class InboxMessagesTest {

    private static final String USER_DELETED_TOPIC = "auth.user.deleted.v1";

    private static final String GRAPH_REFRESH_COMPLETED_TOPIC = "graph-rag.graph-refresh.completed.v1";

    private static final UUID EVENT_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a6b-7c8d9e0f1a2b");

    private static final UUID USER_ID = UUID.fromString("0199a1b2-0000-7000-8000-000000000001");

    private static final UUID REQUEST_ID = UUID.fromString("0199a1b2-0000-7000-8000-000000000002");

    private static final UUID PROJECT_ID = UUID.fromString("0199a1b2-0000-7000-8000-000000000003");

    /** 계약의 예시와 같은 모양의, 성공한 최신화의 payload. */
    private static final String SUCCEEDED = """
            {
              "request_id": "%s",
              "project_id": "%s",
              "outcome": "SUCCEEDED",
              "result": { "bucket": "refresh-bucket", "key": "refresh/p/r/result.json" },
              "error": null,
              "prompt_version": "2026-09-01"
            }
            """.formatted(REQUEST_ID, PROJECT_ID);

    /**
     * 계약의 필수 header 일곱 개를 모두 붙인 record. 선택 header(ce_sequence, traceparent)는 붙이지 않는다 — 없어도
     * 받아들여진다는 것이 이 record 를 쓰는 모든 테스트에서 함께 확인된다.
     *
     * @param subject header ce_subject 에 적을 대상 id
     * @param value 메시지의 value. 없는 record 를 만들려면 null
     */
    private static ConsumerRecord<String, String> record(
            String topic, String type, String source, UUID subject, String value) {
        // 인자는 차례로 topic, partition, offset, key, value 다. partition 과 offset 은 건너뜀 로그에서 확인한다.
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 42L, subject.toString(), value);
        set(record, "ce_specversion", "1.0");
        set(record, "ce_id", EVENT_ID.toString());
        set(record, "ce_type", type);
        set(record, "ce_source", source);
        set(record, "ce_subject", subject.toString());
        set(record, "ce_time", "2026-09-30T10:00:00.123Z");
        set(record, "content-type", "application/json");
        return record;
    }

    /** 계약에 맞는 유저 삭제 record. value 만 바꿔 끼울 수 있다. */
    private static ConsumerRecord<String, String> userDeleted(String value) {
        return record(USER_DELETED_TOPIC, "UserDeleted", "/auth", USER_ID, value);
    }

    /** 계약에 맞는 유저 삭제 record. */
    private static ConsumerRecord<String, String> userDeleted() {
        return userDeleted("{\"user_id\":\"" + USER_ID + "\"}");
    }

    /** 계약에 맞는 최신화 완료 record. value 만 바꿔 끼울 수 있다. */
    private static ConsumerRecord<String, String> graphRefreshCompleted(String value) {
        return record(GRAPH_REFRESH_COMPLETED_TOPIC, "GraphRefreshCompleted", "/graph-rag", REQUEST_ID, value);
    }

    /** header 하나를 이 값으로 둔다. 같은 이름이 이미 있으면 떼고 붙인다. */
    private static void set(ConsumerRecord<String, String> record, String name, String value) {
        record.headers().remove(name).add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    /** 받아들여진 유저 삭제는 header 의 이벤트 id 와 payload 의 유저 id 를 함께 돌려준다. */
    @Test
    void readsAUserDeletion() {
        Optional<Received<UserDeletedEvent>> result = InboxMessages.userDeleted(userDeleted());

        assertThat(result).contains(new Received<>(EVENT_ID, new UserDeletedEvent(USER_ID)));
    }

    /** 성공한 최신화는 결과의 위치가 채워지고 error 는 비어 온다. */
    @Test
    void readsASuccessfulGraphRefresh() {
        Optional<Received<GraphRefreshCompletedEvent>> result =
                InboxMessages.graphRefreshCompleted(graphRefreshCompleted(SUCCEEDED));

        assertThat(result).contains(new Received<>(EVENT_ID, new GraphRefreshCompletedEvent(
                REQUEST_ID, PROJECT_ID, Outcome.SUCCEEDED,
                new Result("refresh-bucket", "refresh/p/r/result.json"), null, "2026-09-01")));
    }

    /** 실패한 최신화는 반대로 error 가 채워지고 result 가 비어 온다. 둘 중 한쪽이 비어 있는 것은 거부 사유가 아니다. */
    @Test
    void readsAFailedGraphRefresh() {
        String failed = """
                {
                  "request_id": "%s",
                  "project_id": "%s",
                  "outcome": "FAILED",
                  "result": null,
                  "error": { "code": "LLM_TIMEOUT", "message": "no answer in time" },
                  "prompt_version": null
                }
                """.formatted(REQUEST_ID, PROJECT_ID);

        Optional<Received<GraphRefreshCompletedEvent>> result =
                InboxMessages.graphRefreshCompleted(graphRefreshCompleted(failed));

        assertThat(result).contains(new Received<>(EVENT_ID, new GraphRefreshCompletedEvent(
                REQUEST_ID, PROJECT_ID, Outcome.FAILED,
                null, new ErrorDetail("LLM_TIMEOUT", "no answer in time"), null)));
    }

    /**
     * 그 topic 에서 처리하지 않는 종류는 건너뛴다. 다른 header 가 틀려 있어도 거부하지 않는다 — 건너뛸 메시지를
     * 검사해서 DLQ 로 보내지 않기 위해서다. 무엇이 왔는지는 로그 한 줄로 남는다.
     */
    @Test
    void skipsATypeItDoesNotHandleAndLeavesALine(CapturedOutput output) {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, "ce_type", "UserRestored");
        // 이 종류를 처리한다면 거부됐을 record 다.
        record.headers().remove("ce_time");

        Optional<Received<UserDeletedEvent>> result = InboxMessages.userDeleted(record);

        assertThat(result).isEmpty();
        // 운영에서 이 문구로 건너뛴 메시지를 찾는다.
        assertThat(output.getOut()).contains(
                "inbox event skipped: topic=auth.user.deleted.v1, partition=0, offset=42, type=UserRestored");
    }

    /** 종류를 적은 header 가 아예 없으면 건너뛰지 않고 거부한다. 무엇인지 모르는 메시지를 조용히 넘기지 않는다. */
    @Test
    void refusesARecordWithoutAType() {
        ConsumerRecord<String, String> record = userDeleted();
        record.headers().remove("ce_type");

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("missing header: ce_type");
    }

    /** 나머지 필수 header 여섯 개는 하나만 빠져도 거부하고, 빠진 header 의 이름을 알린다. */
    @ParameterizedTest
    @ValueSource(strings = {"ce_specversion", "ce_id", "ce_source", "ce_subject", "ce_time", "content-type"})
    void refusesARecordMissingARequiredHeader(String name) {
        ConsumerRecord<String, String> record = userDeleted();
        record.headers().remove(name);

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("missing header: " + name);
    }

    /** header 가 실려 있어도 값이 비어 있으면 없는 것으로 본다. */
    @ParameterizedTest
    @ValueSource(strings = {"ce_type", "ce_specversion", "ce_id", "ce_source", "ce_subject", "ce_time", "content-type"})
    void treatsAnEmptyRequiredHeaderAsMissing(String name) {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, name, "");

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("missing header: " + name);
    }

    /**
     * 공백뿐인 ce_type 은 건너뛰지 않고 거부한다. 길이만 보면 값이 있는 것처럼 보여 "모르는 종류"로 지나가는데,
     * 종류를 적지 않은 것과 같으므로 없는 것으로 다룬다.
     */
    @Test
    void refusesATypeThatIsOnlySpacesInsteadOfSkippingIt() {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, "ce_type", "   ");

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("missing header: ce_type");
    }

    /** Kafka 는 값이 null 인 header 를 허용한다. header 는 붙었지만 값이 없으므로 없는 것으로 다룬다. */
    @Test
    void treatsAHeaderWithoutAValueAsMissing() {
        ConsumerRecord<String, String> record = userDeleted();
        record.headers().remove("ce_source").add("ce_source", null);

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("missing header: ce_source");
    }

    /** 선택 header 는 실려 있어도 보지 않는다. 값이 어떻든 결과가 같다. */
    @Test
    void ignoresTheOptionalHeaders() {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, "ce_sequence", "not a number");
        set(record, "traceparent", "not a trace");

        assertThat(InboxMessages.userDeleted(record)).isPresent();
    }

    /** 계약의 버전이 다르면 header 의 뜻이 달라졌을 수 있으므로 거부한다. */
    @Test
    void refusesAnotherSpecVersion() {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, "ce_specversion", "2.0");

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("invalid header: ce_specversion");
    }

    /** topic 마다 보내는 서비스가 정해져 있다. 유저 삭제 topic 에 graph-rag 가 보낸 것은 거부한다. */
    @Test
    void refusesAUserDeletionFromAnotherService() {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, "ce_source", "/graph-rag");

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("invalid header: ce_source");
    }

    /** 최신화 완료 topic 은 graph-rag 만 보낸다. 기대하는 서비스가 topic 마다 다르다는 것을 확인한다. */
    @Test
    void refusesAGraphRefreshFromAnotherService() {
        ConsumerRecord<String, String> record = graphRefreshCompleted(SUCCEEDED);
        set(record, "ce_source", "/auth");

        assertThatThrownBy(() -> InboxMessages.graphRefreshCompleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("invalid header: ce_source");
    }

    /**
     * content-type 은 글자가 아니라 뜻으로 비교한다. 뒤에 charset 이 붙거나 대문자로 적어도 JSON 이다. 이 header 는
     * 발행하는 코드가 손으로 적는 값이라 표기가 서비스마다 다를 수 있다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"application/json; charset=UTF-8", "application/json;charset=utf-8", "APPLICATION/JSON"})
    void acceptsJsonHoweverTheContentTypeIsWritten(String contentType) {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, "content-type", contentType);

        assertThat(InboxMessages.userDeleted(record)).isPresent();
    }

    /** JSON 이 아닌 종류와, media type 의 형식이 아닌 값은 거부한다. */
    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/cloudevents+json", "json"})
    void refusesAContentTypeThatIsNotJson(String contentType) {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, "content-type", contentType);

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("invalid header: content-type");
    }

    /**
     * 두 id 는 표준 표기의 UUID 여야 한다. "1-2-3-4-5" 는 Java 의 UUID.fromString 이 0 을 채워 받아 주는 표기라서,
     * 따로 막지 않으면 통과한다.
     */
    @ParameterizedTest
    @CsvSource({"ce_id, not-a-uuid", "ce_id, 1-2-3-4-5", "ce_subject, not-a-uuid", "ce_subject, 1-2-3-4-5"})
    void refusesAnIdThatIsNotAStandardUuid(String name, String value) {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, name, value);

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("invalid header: " + name);
    }

    /** 시각은 쓰지 않지만 RFC 3339 로 읽혀야 한다. 시간대가 빠진 것도 읽히지 않는 값이다. */
    @ParameterizedTest
    @ValueSource(strings = {"yesterday", "2026-09-30T10:00:00"})
    void refusesATimeThatCannotBeRead(String time) {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, "ce_time", time);

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("invalid header: ce_time");
    }

    /**
     * 모르는 필드는 무시한다. 발행자가 필드를 먼저 추가하고 받는 쪽이 나중에 따라가므로, 거부하면 발행자가 필드
     * 하나를 더한 순간 모든 메시지가 DLQ 로 간다.
     */
    @Test
    void ignoresPayloadFieldsItDoesNotKnow() {
        String withNewFields = "{\"user_id\":\"" + USER_ID + "\",\"deleted_by\":\"admin\",\"extra\":{\"a\":1}}";

        Optional<Received<UserDeletedEvent>> result = InboxMessages.userDeleted(userDeleted(withNewFields));

        assertThat(result).contains(new Received<>(EVENT_ID, new UserDeletedEvent(USER_ID)));
    }

    /** JSON 이 아닌 value 는 거부한다. 필드를 읽기 전에 깨진 것이라 필드 경로 없이 위치만 알린다. */
    @Test
    void refusesAPayloadThatIsNotJson() {
        assertThatThrownBy(() -> InboxMessages.userDeleted(userDeleted("hello world")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("unreadable payload: tools.jackson.core.exc.StreamReadException (line 1, column 1)")
                // 원래 예외를 원인으로 붙이지 않는다. 원래 예외의 메시지에는 받은 값이 들어 있다.
                .hasNoCause();
    }

    /**
     * 필드의 값이 맞지 않으면 어느 필드인지는 알리고 받은 값은 알리지 않는다. 계약에 없는 outcome 은 조용히 넘기지
     * 않고 거부한다.
     */
    @Test
    void namesTheFieldButNotTheValueWhenAPayloadFieldIsWrong() {
        String unknownOutcome = SUCCEEDED.replace("SUCCEEDED", "CANCELLED");

        assertThatThrownBy(() -> InboxMessages.graphRefreshCompleted(graphRefreshCompleted(unknownOutcome)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageStartingWith("unreadable payload: tools.jackson.databind.exc.InvalidFormatException at ")
                .hasMessageContaining("GraphRefreshCompletedEvent[\"outcome\"]")
                .hasMessageContaining("(line 4, column ")
                .hasMessageNotContaining("CANCELLED")
                .hasNoCause();
    }

    /** 최신화 완료에 항상 있어야 하는 세 필드는 하나만 빠져도 거부한다. */
    @ParameterizedTest
    @ValueSource(strings = {"request_id", "project_id", "outcome"})
    void refusesAGraphRefreshMissingARequiredField(String field) {
        // 세 필드 가운데 하나를 뺀 JSON 을 만든다.
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("request_id", REQUEST_ID.toString());
        fields.put("project_id", PROJECT_ID.toString());
        fields.put("outcome", "SUCCEEDED");
        fields.remove(field);
        String json = fields.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                .collect(Collectors.joining(",", "{", "}"));

        assertThatThrownBy(() -> InboxMessages.graphRefreshCompleted(graphRefreshCompleted(json)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("missing payload field: " + field);
    }

    /**
     * 유저 id 가 없는 유저 삭제는 거부한다. 필드가 없는 것, null 인 것, 빈 글자인 것 모두 JSON library 는 예외 없이
     * null 로 읽기 때문에, 읽은 뒤에 따로 확인해야 잡힌다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"user_id\":null}", "{\"user_id\":\"\"}"})
    void refusesAUserDeletionWithoutAUserId(String json) {
        assertThatThrownBy(() -> InboxMessages.userDeleted(userDeleted(json)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("missing payload field: user_id");
    }

    /** value 가 아예 없는 record 는 Kafka 에서 만들 수 있다. 계약에는 없는 모양이라 거부한다. */
    @Test
    void refusesARecordWithoutAValue() {
        assertThatThrownBy(() -> InboxMessages.userDeleted(userDeleted(null)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("missing payload");
    }

    /** value 가 JSON 의 null 이면 JSON library 는 예외 없이 null 을 돌려준다. 이것도 payload 가 없는 것이다. */
    @Test
    void refusesAPayloadThatIsJsonNull() {
        assertThatThrownBy(() -> InboxMessages.userDeleted(userDeleted("null")))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("missing payload");
    }

    /** header 의 대상과 payload 의 id 가 서로 다른 유저를 가리키면 어느 쪽을 믿을지 알 수 없으므로 거부한다. */
    @Test
    void refusesAUserDeletionWhoseSubjectIsAnotherUser() {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, "ce_subject", UUID.randomUUID().toString());

        assertThatThrownBy(() -> InboxMessages.userDeleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("ce_subject does not match the payload id");
    }

    /**
     * 최신화 완료의 대상은 프로젝트가 아니라 요청이다. ce_subject 에 project_id 가 실려 오면 payload 안에 있는
     * id 이지만 대조할 id 가 아니므로 거부한다.
     */
    @Test
    void comparesAGraphRefreshSubjectWithTheRequestIdNotTheProjectId() {
        ConsumerRecord<String, String> record = graphRefreshCompleted(SUCCEEDED);
        set(record, "ce_subject", PROJECT_ID.toString());

        assertThatThrownBy(() -> InboxMessages.graphRefreshCompleted(record))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("ce_subject does not match the payload id");
    }

    /** 대조는 글자가 아니라 UUID 값으로 한다. 대문자로 적은 것은 같은 id 다. */
    @Test
    void acceptsASubjectThatDiffersOnlyInCase() {
        ConsumerRecord<String, String> record = userDeleted();
        set(record, "ce_subject", USER_ID.toString().toUpperCase());

        assertThat(InboxMessages.userDeleted(record)).isPresent();
    }
}
