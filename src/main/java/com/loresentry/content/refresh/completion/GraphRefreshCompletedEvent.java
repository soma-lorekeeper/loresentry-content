package com.loresentry.content.refresh.completion;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 그래프 최신화가 끝났다. graph-rag 서비스가 {@code graph-rag.graph-refresh.completed.v1} 로 보낸다. 성공과 실패를
 * 같은 이벤트로 알린다.
 *
 * <p>이 package 는 최신화(refresh_runs, refresh_document_drafts)를 맡는다. 이벤트 이름의 "Graph" 는 보내는 graph-rag
 * 쪽의 말이고, content 에서 이 이벤트는 사용자가 요청한 최신화의 결과가 도착했다는 뜻이다. 사용자가 이은 관계를
 * 보여 주는 graph package 와는 다르다. Kafka 에서 받아 이 모양으로 옮기는 것은 inbox package 의
 * {@code InboxMessages} 이고, 이 package 는 Kafka 와 inbox 를 모른다.
 *
 * <p>필드는 계약의 JSON 그대로다. JSON 의 이름이 Java 이름과 다른 필드에만 {@code @JsonProperty} 를 붙였다.
 *
 * <p>{@code requestId}, {@code projectId}, {@code outcome} 은 항상 있어야 하고 {@code InboxMessages} 가 확인한다.
 * {@code result} 와 {@code error} 는 {@code outcome} 에 따라 한쪽이 비어 온다. "성공이면 result 가 있어야 한다"
 * 같은 필드 사이의 규칙은 여기서 보지 않는다 — outcome 으로 갈라 처리하는 쪽이 그 자리에서 확인한다.
 *
 * @param requestId 최신화 요청의 id. 요청을 보낼 때 정한 값이 그대로 돌아와 요청과 결과를 잇는다.
 * @param projectId 대상 프로젝트
 * @param outcome 성공했는지 실패했는지
 * @param result 성공했을 때 결과 파일이 있는 곳. 실패면 null
 * @param error 실패했을 때의 원인. 성공이면 null
 * @param promptVersion 추출에 쓴 규칙의 버전
 */
public record GraphRefreshCompletedEvent(
        @JsonProperty("request_id") UUID requestId,
        @JsonProperty("project_id") UUID projectId,
        Outcome outcome,
        Result result,
        ErrorDetail error,
        @JsonProperty("prompt_version") String promptVersion) {

    /** 최신화의 결과. 계약에 없는 값이 오면 JSON library 가 읽기를 거부하고, 그 메시지는 거부된다. */
    public enum Outcome { SUCCEEDED, FAILED }

    /** 결과 파일의 S3 위치. 결과 자체는 커서 이벤트에 싣지 않고 위치만 싣는다. */
    public record Result(String bucket, String key) {
    }

    /** 실패의 원인. 이름을 Error 로 하면 Java 의 {@code java.lang.Error} 를 가려서 달리 지었다. */
    public record ErrorDetail(String code, String message) {
    }
}
