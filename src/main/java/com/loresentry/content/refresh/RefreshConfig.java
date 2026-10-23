package com.loresentry.content.refresh;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 그래프 최신화 bucket 전용 S3 client 를 만든다.
 *
 * <p>미디어 client(MediaConfig)는 미디어 bucket 의 리전으로 만들어지므로, 최신화 bucket 은 자기 리전
 * ({@code graph-refresh.region})으로 client 를 따로 둔다. 이 client 는 타입만으로는 주입되지 않아
 * ({@code defaultCandidate = false}) {@code S3Client} 를 받는 미디어 쪽에 섞이지 않고, 이름
 * ({@code refreshS3Client})을 지정한 곳에만 들어간다.
 */
@Configuration
class RefreshConfig {

    @Bean(defaultCandidate = false)
    S3Client refreshS3Client(@Value("${graph-refresh.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }
}
