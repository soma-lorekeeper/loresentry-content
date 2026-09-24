package com.loresentry.content.image;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ImageDtos {

    private ImageDtos() {
    }

    /**
     * {@code size_bytes} 는 브라우저의 {@code File.size} 다. 서명에 들어가므로 실제 업로드와
     * 어긋나면 S3 가 거절한다 — 티켓이 선언한 것보다 큰 파일을 올릴 수 없다.
     */
    public record TicketRequest(
            @JsonProperty("file_name") String fileName,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("size_bytes") Long sizeBytes) {
    }

    /** 브라우저가 S3 로 직접 PUT 하는 데 필요한 전부. 바이트는 이 서비스를 지나지 않는다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Ticket(
            @JsonProperty("image_id") UUID imageId,
            String key,
            @JsonProperty("upload_url") String uploadUrl,
            String method,
            Map<String, String> headers,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("public_url") String publicUrl) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Image(
            @JsonProperty("image_id") UUID imageId,
            @JsonProperty("project_id") UUID projectId,
            @JsonProperty("file_name") String fileName,
            String key,
            @JsonProperty("content_type") String contentType,
            @JsonProperty("size_bytes") long sizeBytes,
            String status,
            @JsonProperty("public_url") String publicUrl,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("committed_at") OffsetDateTime committedAt) {
    }
}
