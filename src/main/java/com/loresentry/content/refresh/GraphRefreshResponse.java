package com.loresentry.content.refresh;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 최신화 요청의 응답.
 *
 * @param requestId 만든 요청의 id. 바뀐 문서가 없어 요청을 만들지 않았으면 null 이다
 * @param files     이번 요청에 실은 문서와 revision. 이 문서들은 최신화가 끝날 때(확정·변경 없음·실패·시간 초과)까지
 *                  잠긴다 — 화면은 이 목록으로 잠김을 표시한다
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GraphRefreshResponse(
        @JsonProperty("request_id") UUID requestId,
        Status status,
        List<File> files) {

    public enum Status {
        /** graph-rag 에 보냈고 결과를 기다린다. */
        GENERATING,
        /** 지난 최신화 이후 바뀐 문서가 없다. 아무것도 기록하거나 보내지 않았다. */
        NO_CHANGES
    }

    public record File(
            @JsonProperty("file_id") UUID fileId,
            @JsonProperty("revision_no") long revisionNo) {
    }

    static GraphRefreshResponse noChanges() {
        return new GraphRefreshResponse(null, Status.NO_CHANGES, List.of());
    }
}
