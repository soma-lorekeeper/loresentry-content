package com.loresentry.content.event;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code content.graph-refresh.requested.v1} 의 GraphRefreshRequested value. 문서 본문은 S3 의 {@link RefreshInput} 에
 * 있고, 이 이벤트는 그 위치와 무엇을 실었는지만 말한다. 어떤 모델·프롬프트로 처리할지는 graph-rag 가 정한다.
 *
 * @param userId           최신화를 요청한 사용자. graph-rag 가 관계 문서를 더 가져갈 때 content 에 이 사용자로 묻는다
 * @param inputFingerprint S3 입력 바이트 전체의 sha256(소문자 16진수 64자). graph-rag 가 읽은 바이트와 대조한다
 * @param files            S3 입력에 실은 문서와 revision. 입력의 문서 목록과 정확히 같다
 * @param requiredEvents   프로젝트 문서마다 가장 최근 FileChanged 이벤트의 id. graph-rag 는 이것을 모두 그래프에
 *                         반영한 뒤에 처리한다
 */
public record GraphRefreshRequested(
        @JsonProperty("project_id") UUID projectId,
        @JsonProperty("request_id") UUID requestId,
        @JsonProperty("user_id") UUID userId,
        @JsonProperty("input_fingerprint") String inputFingerprint,
        Input input,
        List<File> files,
        @JsonProperty("required_events") List<UUID> requiredEvents) {

    /** S3 입력의 위치. */
    public record Input(String bucket, String key) {
    }

    /** 실은 문서 하나. 휴지통·삭제 문서는 보내지 않으므로 모두 활성이다. */
    public record File(
            @JsonProperty("file_id") UUID fileId,
            @JsonProperty("revision_no") long revisionNo) {
    }
}
