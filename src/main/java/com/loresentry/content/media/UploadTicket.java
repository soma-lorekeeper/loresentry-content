package com.loresentry.content.media;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record UploadTicket(
        UUID imageId,
        String key,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        Instant expiresAt,
        String publicUrl) {
}
