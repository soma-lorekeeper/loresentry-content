package com.loresentry.content.media;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ImageRecord(
        UUID id,
        UUID projectId,
        String fileName,
        String s3Key,
        String contentType,
        long sizeBytes,
        ImageStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime committedAt) {
}
