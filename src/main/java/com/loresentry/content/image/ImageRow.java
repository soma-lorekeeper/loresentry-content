package com.loresentry.content.image;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ImageRow(
        UUID id,
        UUID projectId,
        String fileName,
        String key,
        String contentType,
        long sizeBytes,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime committedAt) {

    public boolean isCommitted() {
        return "COMMITTED".equals(status);
    }
}
