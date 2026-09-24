package com.loresentry.content.search;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SearchResponses {

    private SearchResponses() {
    }

    /**
     * 일치한 본문 조각. 화면이 굵게 표시할 구간을 서버가 잘라 준다 — 클라이언트가 본문 전체를
     * 받아 다시 찾으면 목록 하나에 문서 전체가 실려 온다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snippet(String before, String match, String after) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Hit(
            @JsonProperty("file_id") UUID fileId,
            String title,
            @JsonProperty("folder_code") String folderCode,
            @JsonProperty("episode_name") String episodeName,
            Snippet snippet,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {
    }

    public record Results(List<Hit> hits) {
    }
}
