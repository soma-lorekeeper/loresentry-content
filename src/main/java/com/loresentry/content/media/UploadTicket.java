package com.loresentry.content.media;

import java.time.Instant;
import java.util.Map;

public record UploadTicket(
        String key,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        Instant expiresAt,
        String publicUrl) {
}
