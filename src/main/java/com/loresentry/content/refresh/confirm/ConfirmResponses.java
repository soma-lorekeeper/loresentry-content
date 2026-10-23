package com.loresentry.content.refresh.confirm;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** 최신화 확정 API 의 응답 모양. */
public final class ConfirmResponses {

    private ConfirmResponses() {
    }

    /**
     * 확정 결과. 작업본마다 무엇이 되었는지 알려 준다.
     *
     * @param created   만든 새 설정 문서
     * @param updated   내용이나 관계가 바뀐 기존 문서
     * @param unchanged 바뀐 것이 없던 작업본(제안을 모두 거절했거나 새 문서를 만들지 않기로 함)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Confirmed(
            @JsonProperty("request_id") UUID requestId,
            String status,
            List<Created> created,
            List<Updated> updated,
            List<UUID> unchanged) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Created(
            @JsonProperty("draft_id") UUID draftId,
            @JsonProperty("candidate_id") String candidateId,
            @JsonProperty("file_id") UUID fileId) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Updated(
            @JsonProperty("draft_id") UUID draftId,
            @JsonProperty("file_id") UUID fileId,
            @JsonProperty("revision_no") long revisionNo) {
    }
}
