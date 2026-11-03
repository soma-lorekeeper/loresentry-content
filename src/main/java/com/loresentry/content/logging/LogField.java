package com.loresentry.content.logging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 로그 한 줄에 붙일 수 있는 필드. 이름은 LOG_SCHEMA.md 5절의 ECS 필드와 {@code loresentry.*} 필드만 쓴다.
 * gateway의 같은 이름 클래스와 같은 규칙이다(content가 쓰지 않는 나가는 HTTP 호출 필드는 없고, Kafka·CloudEvents 필드가 있다).
 *
 * <p>생성자를 막고 정적 팩토리만 열어 두었다. 그래서 서비스 코드가 임의의 이름이나 값(원고, 제목, 메모, 검색어, 요청 본문)을
 * 필드로 넣을 수 없다. 새 필드가 필요하면 LOG_SCHEMA.md 표에 먼저 추가한 뒤 여기에 팩토리를 만든다.
 *
 * <p>필드 하나가 이름·값 쌍을 여러 개 가질 수 있다. 예를 들어 문자열 입력값은 {@code param.title: "[redacted]"}와
 * {@code param.title_length: 7} 두 쌍이 된다. 값이 null인 필드는 줄에서 빠진다.
 *
 * <p>입력값({@link #param})·결과({@link #result})는 LOG_SCHEMA.md 5절 "입력값과 결과" 규칙대로 값을 바꾼다.
 * <ul>
 *   <li>숫자·불리언·enum·UUID: 그대로</li>
 *   <li>문자열: 늘 {@code [redacted]}와 {@code <이름>_length}. 제목·본문·검색어 같은 내용이 남지 않는다</li>
 *   <li>허용 목록이 있는 코드 문자열({@link #code}): 목록 안이면 그대로, 밖이면 {@code other}</li>
 *   <li>컬렉션·Map: {@code <이름>_count}, JSON 본문·byte 배열: {@code <이름>_bytes}</li>
 *   <li>그 밖의 객체(DTO 등): 받는 메서드가 없어 컴파일되지 않는다. 필요한 숫자·코드만 골라 넘긴다</li>
 * </ul>
 * 이름은 파라미터 이름을 snake_case로 쓰고, 같은 이름은 늘 같은 타입으로 쓴다(ES는 처음 들어온 값으로 타입을 정한다).
 */
public final class LogField {
    /** 가린 문자열 자리에 남기는 값. Logstash mask.rb의 표시와 같다. */
    static final String REDACTED = "[redacted]";
    /** 허용 목록 밖의 코드 값을 대신하는 값. */
    static final String OTHER = "other";
    /** 안전한 스택 트레이스에 남기는 원인 수·원인마다의 프레임 수 상한. */
    private static final int MAX_CAUSES = 8;
    private static final int MAX_FRAMES = 30;
    private static final String PARAM = "loresentry.param.";
    private static final String RESULT = "loresentry.result.";

    private final List<Map.Entry<String, Object>> entries;

    private LogField(List<Map.Entry<String, Object>> entries) {
        this.entries = entries;
    }

    /** 이 필드가 줄에 더하는 이름·값 쌍(점 이름). AppLogger가 key-value로 넘긴다. */
    List<Map.Entry<String, Object>> entries() {
        return entries;
    }

    private static LogField of(String name, Object value) {
        return value == null ? null : new LogField(List.of(Map.entry(name, value)));
    }

    private static LogField uuid(String name, UUID value) {
        return of(name, value == null ? null : value.toString());
    }

    // ---------------------------------------------------------------- 도메인 ID (loresentry.*, keyword)
    // 이 줄의 대상·대상이 속한 범위·새로 만든 대상만 여기에 넣는다. 참조만 하는 ID는 param("<역할>_<종류>_id", id)로 넣는다.

    public static LogField projectId(UUID id) {
        return uuid("loresentry.project_id", id);
    }

    public static LogField documentId(UUID id) {
        return uuid("loresentry.document_id", id);
    }

    public static LogField fileId(UUID id) {
        return uuid("loresentry.file_id", id);
    }

    /** ai-chat 대화 세션. 인증 세션과 헷갈리지 않게 chat 아래에 둔다. */
    public static LogField chatSessionId(UUID id) {
        return uuid("loresentry.chat.session_id", id);
    }

    public static LogField chatTurnId(UUID id) {
        return uuid("loresentry.chat.turn_id", id);
    }

    /**
     * 관계도 최신화 요청(graph-refresh requestId). content 의 run(refresh_runs.id)도 같은 값이라 이 필드 하나로 남긴다.
     * 그래서 요청 하나를 gateway → content → graph-rag 까지 같은 필드로 따라간다.
     */
    public static LogField refreshRequestId(UUID id) {
        return uuid("loresentry.refresh.request_id", id);
    }

    /** 관계도 최신화 결과를 사용자가 고치는 초안. */
    public static LogField refreshDraftId(UUID id) {
        return uuid("loresentry.refresh.draft_id", id);
    }

    /**
     * 문서 저장의 중복 방지 키(X-Save-Id). 같은 저장을 다시 보냈는지 이 값으로 찾는다.
     * 브라우저가 만든 문자열이라 UUID 모양일 때만 남긴다.
     */
    public static LogField saveId(String header) {
        if (header == null) return null;
        try {
            return uuid("loresentry.save_id", UUID.fromString(header));
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    /** 회차 폴더(episode_folders). */
    public static LogField episodeId(UUID id) {
        return uuid("loresentry.episode_id", id);
    }

    public static LogField memoId(UUID id) {
        return uuid("loresentry.memo_id", id);
    }

    /** 문서의 저장 버전. */
    public static LogField versionId(UUID id) {
        return uuid("loresentry.version_id", id);
    }

    /** 업로드한 이미지(S3 객체의 content 쪽 ID). */
    public static LogField imageId(UUID id) {
        return uuid("loresentry.image_id", id);
    }

    // ---------------------------------------------------------------- Kafka·CloudEvents (loresentry.*)
    // ECS 8.11에 메시징 필드 묶음이 없어 loresentry 아래에 둔다(LOG_SCHEMA.md 5절 필드 표).

    public static LogField kafkaTopic(String topic) {
        return of("loresentry.kafka.topic", topic);
    }

    public static LogField kafkaPartition(int partition) {
        return of("loresentry.kafka.partition", (long) partition);
    }

    public static LogField kafkaOffset(long offset) {
        return of("loresentry.kafka.offset", offset);
    }

    /** 이벤트 ID(ce_id). 보낸 쪽 outbox 행의 ID와 같다. 받은 쪽은 이 값으로 중복을 거른다. */
    public static LogField cloudeventId(UUID id) {
        return uuid("loresentry.cloudevent.id", id);
    }

    /** 이벤트 종류(ce_type). 우리가 보내는 이벤트의 이름(FileChanged 등)이라 그대로 남긴다. 받은 메시지의 값에는 쓰지 않는다. */
    public static LogField cloudeventType(String type) {
        return of("loresentry.cloudevent.type", type);
    }

    // ---------------------------------------------------------------- 스케줄 작업 (loresentry.*)

    /**
     * 스케줄 작업 이름(outbox-relay, outbox-purge, inbox-purge, graph-refresh-timeout). 작업 1회의 시작점이 붙여 그 회차의 모든 줄에
     * 남는다. HTTP 요청의 http.route 처럼, 작업 하나가 남긴 줄을 이 값으로 모아 본다. 코드에 정해 둔 이름만 넘긴다.
     */
    public static LogField scheduleName(String name) {
        return of("loresentry.schedule.name", name);
    }

    // ---------------------------------------------------------------- HTTP (ECS·OpenTelemetry)

    /** 요청 메서드(GET, POST 등). */
    public static LogField httpMethod(String method) {
        return of("http.request.method", method);
    }

    /**
     * 이 서비스가 받은 요청의 실제 경로(쿼리 제외). gateway가 부른 경로다. RequestLogFilter가 요청 하나의 모든 줄에 붙인다.
     * 쿼리에는 검색어가 들어갈 수 있어 남기지 않는다.
     */
    public static LogField urlPath(String path) {
        return of("url.path", path);
    }

    /**
     * Spring이 매칭한 경로 템플릿(/projects/{projectId}). 엔드포인트별로 묶어 볼 때 쓴다. OpenTelemetry http.route와 같은 뜻이다.
     * RequestLogFilter가 요청 시작 때 찾아 요청 하나의 모든 줄에 붙인다. 맞는 경로가 없는 요청(404)에는 없다.
     */
    public static LogField route(String pattern) {
        return of("http.route", pattern);
    }

    public static LogField statusCode(int status) {
        return of("http.response.status_code", (long) status);
    }

    // ---------------------------------------------------------------- 시간·에러 (ECS)

    /** 걸린 시간. ECS event.duration은 나노초다. */
    public static LogField duration(Duration duration) {
        return of("event.duration", duration == null ? null : duration.toNanos());
    }

    /** 예외 없이 실패 종류만 남길 때. 예외 클래스 이름이나 우리 오류 코드(INTERNAL_ERROR 등). */
    public static LogField errorType(String type) {
        return of("error.type", type);
    }

    /**
     * 예외 메시지를 뺀 스택 트레이스({@code error.stack_trace}). 원인마다 클래스 이름과 프레임만 적는다.
     * 예외 메시지에는 SQL·요청 값이 섞일 수 있어서, 처리하지 못한 오류(500)의 원인을 남길 때 예외 대신 이 필드를 넘긴다.
     */
    public static LogField safeStackTrace(Throwable failure) {
        if (failure == null) return null;
        StringBuilder trace = new StringBuilder();
        int causes = 0;
        for (Throwable cause = failure; cause != null && causes < MAX_CAUSES; cause = cause.getCause(), causes++) {
            trace.append(cause.getClass().getName()).append('\n');
            Arrays.stream(cause.getStackTrace()).limit(MAX_FRAMES)
                    .forEach(frame -> trace.append("  at ").append(frame).append('\n'));
            // 원인이 자기 자신을 가리키는 예외는 거기서 멈춘다
            if (cause.getCause() == cause) break;
        }
        return of("error.stack_trace", trace.toString());
    }

    // ---------------------------------------------------------------- 입력값 (loresentry.param.*)

    public static LogField param(String name, Number value) {
        return of(PARAM + name, value);
    }

    public static LogField param(String name, Boolean value) {
        return of(PARAM + name, value);
    }

    public static LogField param(String name, Enum<?> value) {
        return of(PARAM + name, value == null ? null : value.name());
    }

    /** 참조만 하는 ID(이동할 위치, 원본, 연결 상대 등)는 역할을 붙인 이름으로: param("target_folder_id", id). */
    public static LogField param(String name, UUID value) {
        return uuid(PARAM + name, value);
    }

    /** 문자열은 내용을 남기지 않는다: [redacted]와 길이. */
    public static LogField param(String name, String value) {
        return masked(PARAM, name, value);
    }

    public static LogField param(String name, Collection<?> value) {
        return of(PARAM + name + "_count", value == null ? null : (long) value.size());
    }

    public static LogField param(String name, Map<?, ?> value) {
        return of(PARAM + name + "_count", value == null ? null : (long) value.size());
    }

    /** JSON 본문은 내용 대신 바이트 수만 남긴다. */
    public static LogField param(String name, JsonNode value) {
        return of(PARAM + name + "_bytes", value == null ? null : jsonBytes(value));
    }

    public static LogField param(String name, byte[] value) {
        return of(PARAM + name + "_bytes", value == null ? null : (long) value.length);
    }

    /**
     * 정해진 낱말 중 하나인 코드 문자열(scope, kind, locale 등). 허용 목록 안이면 그대로, 밖이면 other로 남긴다.
     * 사용자가 이 칸에 자유 텍스트를 보내도 그 값은 남지 않는다.
     */
    public static LogField code(String name, String value, Set<String> allowed) {
        return of(PARAM + name, value == null ? null : (allowed.contains(value) ? value : OTHER));
    }

    // ---------------------------------------------------------------- 처리 결과 (loresentry.result.*)

    public static LogField result(String name, Number value) {
        return of(RESULT + name, value);
    }

    public static LogField result(String name, Boolean value) {
        return of(RESULT + name, value);
    }

    public static LogField result(String name, Enum<?> value) {
        return of(RESULT + name, value == null ? null : value.name());
    }

    public static LogField result(String name, UUID value) {
        return uuid(RESULT + name, value);
    }

    public static LogField result(String name, String value) {
        return masked(RESULT, name, value);
    }

    public static LogField result(String name, Collection<?> value) {
        return of(RESULT + name + "_count", value == null ? null : (long) value.size());
    }

    public static LogField result(String name, Map<?, ?> value) {
        return of(RESULT + name + "_count", value == null ? null : (long) value.size());
    }

    public static LogField result(String name, JsonNode value) {
        return of(RESULT + name + "_bytes", value == null ? null : jsonBytes(value));
    }

    /** 결과 코드 문자열(상태 이름 등). 허용 목록 밖이면 other. */
    public static LogField resultCode(String name, String value, Set<String> allowed) {
        return of(RESULT + name, value == null ? null : (allowed.contains(value) ? value : OTHER));
    }

    private static LogField masked(String prefix, String name, String value) {
        if (value == null) return null;
        return new LogField(List.of(Map.entry(prefix + name, REDACTED), Map.entry(prefix + name + "_length", (long) value.length())));
    }

    private static long jsonBytes(JsonNode value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
