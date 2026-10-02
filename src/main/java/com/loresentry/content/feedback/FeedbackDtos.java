package com.loresentry.content.feedback;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class FeedbackDtos {

    private FeedbackDtos() {
    }

    public record CreateRequest(String category, String message, String page, String client) {
    }

    public record Created(UUID id, @JsonProperty("created_at") OffsetDateTime createdAt) {
    }
}
