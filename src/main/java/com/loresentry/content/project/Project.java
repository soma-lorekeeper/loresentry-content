package com.loresentry.content.project;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code projects} 한 행. 도메인 표현이고 JSON 모양은 {@link ProjectResponse}가 정한다.
 */
public record Project(
        UUID id,
        UUID ownerUserId,
        String name,
        String description,
        OffsetDateTime trashedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public boolean isTrashed() {
        return trashedAt != null;
    }
}
