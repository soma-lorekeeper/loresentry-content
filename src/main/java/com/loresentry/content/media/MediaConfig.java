package com.loresentry.content.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {

    @Bean
    public S3Client s3Client(MediaProperties properties) {
        return S3Client.builder()
                .region(Region.of(properties.region()))
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(MediaProperties properties) {
        return S3Presigner.builder()
                .region(Region.of(properties.region()))
                .build();
    }
}
