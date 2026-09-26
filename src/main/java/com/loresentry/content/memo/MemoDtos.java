package com.loresentry.content.memo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class MemoDtos {

    private MemoDtos() {
    }

    /** {@code scope} 가 {@code file} 이면 {@code document_id} 가 필요하다. */
    public record CreateRequest(
            String scope,
            @JsonProperty("document_id") UUID documentId,
            String title,
            String body) {
    }

    /** 제목은 선택이다. 화면은 제목이 없으면 본문 첫 문장을 카드 제목으로 쓴다. */
    public record UpdateRequest(String title, String body) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Memo(
            UUID id,
            @JsonProperty("project_id") UUID projectId,
            String scope,
            @JsonProperty("document_id") UUID documentId,
            String title,
            String body,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {
    }

    public record MemoList(List<Memo> memos) {
    }
}
