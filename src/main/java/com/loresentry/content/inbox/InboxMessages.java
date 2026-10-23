package com.loresentry.content.inbox;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

import com.loresentry.content.user.UserDeletedEvent;
import com.loresentry.content.refresh.completion.GraphRefreshCompletedEvent;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka 에서 받은 record 하나를 우리 이벤트로 옮긴다. {@code OutboxMessages} 가 보내는 쪽의 계약을 모아 둔 것처럼,
 * 받는 쪽의 계약(팀 Kafka 설계 문서의 "Message 계약")이 이 클래스 한 곳에 모여 있다.
 *
 * <p>record 하나의 결과는 셋 중 하나이고, 서로 다른 모양으로 돌려준다.
 * <ul>
 *   <li>받아들임 — 이벤트 id 와 이벤트 객체를 {@link Received} 에 담아 돌려준다.</li>
 *   <li>건너뜀 — 그 topic 에서 우리가 처리하지 않는 종류({@code ce_type})다. 경고를 한 줄 남기고 빈 값을 돌려준다.
 *       발행자가 topic 에 새 종류를 더하는 것은 정상적인 변경이라 실패로 치지 않는다.</li>
 *   <li>거부 — 계약에 맞지 않는다. {@link InvalidEventException} 을 던진다. 같은 메시지를 다시 읽어도 결과가
 *       같다.</li>
 * </ul>
 *
 * <p>검사 순서는 종류({@code ce_type}) → 나머지 필수 header → payload 읽기 → 있어야 하는 필드 → {@code ce_subject}
 * 와 payload 의 id 대조다. 여러 군데가 틀려도 처음 걸린 하나만 알린다.
 *
 * <p>거부하는 메시지에는 어느 검사에 걸렸는지만 적고 받은 값은 적지 않는다. 로그에 받은 값을 적는 곳은 건너뜀
 * 로그의 {@code ce_type} 하나뿐이다.
 */
final class InboxMessages {

    /** 로그를 남기는 도구. 여기서는 건너뛴 메시지의 경고만 남긴다. */
    private static final Logger log = LoggerFactory.getLogger(InboxMessages.class);

    /**
     * JSON 을 읽는 도구. 앱의 기본 설정(JacksonConfiguration)을 쓰지 않고 따로 만든다. 기본 설정은 모르는 필드가
     * 오면 거부하는데, 그것은 API 요청에 맞는 엄격함이다. 이벤트는 발행자가 먼저 필드를 추가하고 받는 쪽이 나중에
     * 따라가므로, 계약이 "모르는 필드는 무시"라고 정했다.
     */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private InboxMessages() {
    }

    /**
     * {@code auth.user.deleted.v1} 에서 받은 record 를 유저 삭제 이벤트로 옮긴다.
     *
     * @return 받아들였으면 이벤트, 이 topic 에서 처리하지 않는 종류면 빈 값
     * @throws InvalidEventException 계약에 맞지 않을 때
     */
    static Optional<Received<UserDeletedEvent>> userDeleted(ConsumerRecord<String, String> record) {
        // 1. 우리가 처리하는 종류인가. 아니면 다른 것은 보지 않고 건너뛴다.
        if (!isExpectedType(record, "UserDeleted")) {
            return Optional.empty();
        }
        // 2. 나머지 필수 header. 이 topic 에는 authentication 서비스만 보낸다.
        Ids ids = checkHeaders(record, "/auth");
        // 3. payload 를 객체로 읽는다.
        UserDeletedEvent event = readPayload(record, UserDeletedEvent.class);
        // 4. 항상 있어야 하는 필드.
        requireField(event.userId(), "user_id");
        // 5. header 의 대상과 payload 의 id 가 같은 유저를 가리키는가. 글자가 아니라 UUID 값으로 비교하므로
        //    대소문자만 다른 것은 같다고 본다.
        if (!ids.subject().equals(event.userId())) {
            throw new InvalidEventException("ce_subject does not match the payload id");
        }
        return Optional.of(new Received<>(ids.eventId(), event));
    }

    /**
     * {@code graph-rag.graph-refresh.completed.v1} 에서 받은 record 를 최신화 완료 이벤트로 옮긴다.
     *
     * @return 받아들였으면 이벤트, 이 topic 에서 처리하지 않는 종류면 빈 값
     * @throws InvalidEventException 계약에 맞지 않을 때
     */
    static Optional<Received<GraphRefreshCompletedEvent>> graphRefreshCompleted(
            ConsumerRecord<String, String> record) {
        // 1. 우리가 처리하는 종류인가.
        if (!isExpectedType(record, "GraphRefreshCompleted")) {
            return Optional.empty();
        }
        // 2. 나머지 필수 header. 이 topic 에는 graph-rag 서비스만 보낸다.
        Ids ids = checkHeaders(record, "/graph-rag");
        // 3. payload 를 객체로 읽는다. outcome 이 계약에 없는 값이면 여기서 거부된다.
        GraphRefreshCompletedEvent event = readPayload(record, GraphRefreshCompletedEvent.class);
        // 4. 항상 있어야 하는 필드. result, error, prompt_version 은 비어 올 수 있어 보지 않는다.
        requireField(event.requestId(), "request_id");
        requireField(event.projectId(), "project_id");
        requireField(event.outcome(), "outcome");
        // 5. 이 이벤트의 대상은 프로젝트가 아니라 요청이다. ce_subject 는 request_id 와 같아야 한다.
        if (!ids.subject().equals(event.requestId())) {
            throw new InvalidEventException("ce_subject does not match the payload id");
        }
        return Optional.of(new Received<>(ids.eventId(), event));
    }

    /** header 에서 읽은 두 id. eventId 는 {@code ce_id}, subject 는 {@code ce_subject} 다. */
    private record Ids(UUID eventId, UUID subject) {
    }

    /**
     * 이 record 가 기대한 종류인지 본다. {@code ce_type} 을 가장 먼저 보는 이유는, 모르는 종류의 메시지는 다른
     * header 가 틀려도 거부하지 않고 건너뛰어야 하기 때문이다.
     *
     * @return 기대한 종류면 true. 다른 종류면 경고를 남기고 false
     * @throws InvalidEventException {@code ce_type} 이 아예 없을 때. 무엇인지 알 수 없는 메시지는 건너뛰지 않는다.
     */
    private static boolean isExpectedType(ConsumerRecord<String, String> record, String expectedType) {
        String type = required(record, "ce_type");
        if (expectedType.equals(type)) {
            return true;
        }
        // 이 문구로 로그를 찾는다. 새 종류가 추가된 것인지 이름을 잘못 적은 것인지 볼 수 있게 받은 값을 적는다.
        log.warn("inbox event skipped: topic={}, partition={}, offset={}, type={}",
                record.topic(), record.partition(), record.offset(), type);
        return false;
    }

    /**
     * {@code ce_type} 을 뺀 나머지 필수 header 를 계약의 순서대로 검사하고, 그중 쓰는 값인 두 id 를 돌려준다.
     * 선택 header({@code ce_sequence}, {@code traceparent})는 보지 않는다.
     *
     * @param expectedSource 이 topic 에 보내는 서비스. {@code ce_source} 가 이 값이어야 한다.
     * @throws InvalidEventException 하나라도 없거나 틀릴 때
     */
    private static Ids checkHeaders(ConsumerRecord<String, String> record, String expectedSource) {
        // 계약의 버전. 다른 값이면 header 의 뜻 자체가 달라졌을 수 있다.
        if (!"1.0".equals(required(record, "ce_specversion"))) {
            throw new InvalidEventException("invalid header: ce_specversion");
        }
        UUID eventId = uuid(record, "ce_id");
        if (!expectedSource.equals(required(record, "ce_source"))) {
            throw new InvalidEventException("invalid header: ce_source");
        }
        UUID subject = uuid(record, "ce_subject");
        // 시각은 쓰지 않는다. RFC 3339 시각으로 읽히는지만 본다.
        try {
            OffsetDateTime.parse(required(record, "ce_time"));
        } catch (DateTimeParseException notATime) {
            throw new InvalidEventException("invalid header: ce_time");
        }
        // 글자를 그대로 비교하지 않고 media type 으로 읽어 종류만 비교한다. "application/json; charset=UTF-8" 이나
        // 대문자로 적은 값은 뜻이 같으므로 통과시킨다.
        try {
            if (!MimeTypeUtils.parseMimeType(required(record, "content-type"))
                    .equalsTypeAndSubtype(MimeTypeUtils.APPLICATION_JSON)) {
                throw new InvalidEventException("invalid header: content-type");
            }
        } catch (InvalidMimeTypeException notAMediaType) {
            throw new InvalidEventException("invalid header: content-type");
        }
        return new Ids(eventId, subject);
    }

    /**
     * 필수 header 하나를 글자로 읽는다.
     *
     * @throws InvalidEventException header 가 없거나 비어 있을 때. 메시지는 {@code missing header: <이름>}
     */
    private static String required(ConsumerRecord<String, String> record, String name) {
        // 같은 이름이 여러 개면 마지막 것을 준다. 계약은 같은 이름을 하나만 두므로 고를 일은 없다. 없으면 null 이다.
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            throw new InvalidEventException("missing header: " + name);
        }

        // header 는 붙었는데 값이 없는 경우. Kafka 는 값이 null 인 header 를 허용한다.
        byte[] bytes = header.value();
        if (bytes == null) {
            throw new InvalidEventException("missing header: " + name);
        }

        // 계약이 header 의 값을 UTF-8 글자로 정했다. 공백뿐인 값도 넣지 않은 것으로 본다 — 그렇지 않으면 공백뿐인
        // ce_type 이 "모르는 종류"로 분류되어 거부되지 않고 건너뛰어진다.
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.isBlank()) {
            throw new InvalidEventException("missing header: " + name);
        }

        return value;
    }

    /**
     * 필수 header 하나를 UUID 로 읽는다.
     *
     * @throws InvalidEventException 없거나, 표준 표기의 UUID 가 아닐 때
     */
    private static UUID uuid(ConsumerRecord<String, String> record, String name) {
        String value = required(record, name);
        // UUID.fromString 은 "1-2-3-4-5" 처럼 짧은 묶음도 받아 0 으로 채운다. 그러면 서로 다른 글자가 같은 UUID 가
        // 되어 inbox 에서 같은 이벤트로 취급되므로, 표준 표기의 길이(36)인지 먼저 본다.
        if (value.length() != 36) {
            throw new InvalidEventException("invalid header: " + name);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            throw new InvalidEventException("invalid header: " + name);
        }
    }

    /**
     * record 의 value(JSON)를 객체로 읽는다. 모르는 필드는 무시한다.
     *
     * <p>읽기에 실패하면 원래 예외의 종류와, 어느 필드의 어디에서 났는지만 메시지에 담는다. 원래 예외의 메시지에는
     * 받은 값이 들어 있어서 옮기지 않고, 원인(cause)으로도 붙이지 않는다.
     *
     * @throws InvalidEventException value 가 없거나, JSON 이 아니거나, 필드의 타입이나 값이 맞지 않을 때
     */
    private static <T> T readPayload(ConsumerRecord<String, String> record, Class<T> type) {
        // Kafka 는 value 가 없는 record 를 허용한다. JSON library 에 null 을 넘기면 계약과 상관없는 예외가 나므로
        // 먼저 본다.
        if (record.value() == null) {
            throw new InvalidEventException("missing payload");
        }
        T payload;
        try {
            payload = JSON.readValue(record.value(), type);
        } catch (JacksonException failure) {
            StringBuilder message = new StringBuilder("unreadable payload: ").append(failure.getClass().getName());
            // 필드 경로는 필드를 읽다가 난 실패에만 있다. JSON 자체가 깨졌으면 비어 있다.
            if (!failure.getPathReference().isEmpty()) {
                message.append(" at ").append(failure.getPathReference());
            }
            TokenStreamLocation location = failure.getLocation();
            if (location != null) {
                message.append(" (line ").append(location.getLineNr())
                        .append(", column ").append(location.getColumnNr()).append(')');
            }
            throw new InvalidEventException(message.toString());
        }
        // value 가 JSON 의 null 이면 library 는 예외 없이 null 을 돌려준다.
        if (payload == null) {
            throw new InvalidEventException("missing payload");
        }
        return payload;
    }

    /**
     * 항상 있어야 하는 payload 필드를 확인한다. JSON library 는 필드가 없거나 null 이어도 예외 없이 null 을 넣으므로
     * 읽은 뒤에 따로 본다.
     *
     * @param name JSON 에서의 필드 이름. 메시지에 적는다.
     * @throws InvalidEventException 값이 null 일 때
     */
    private static void requireField(Object value, String name) {
        if (value == null) {
            throw new InvalidEventException("missing payload field: " + name);
        }
    }
}
