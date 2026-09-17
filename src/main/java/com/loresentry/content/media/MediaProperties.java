package com.loresentry.content.media;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "media")
public record MediaProperties(
        String bucket,
        String region,
        String publicBaseUrl,
        Duration uploadUrlTtl,
        long maxSizeBytes,
        List<String> allowedContentTypes) {
}
