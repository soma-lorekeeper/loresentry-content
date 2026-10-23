package com.loresentry.content.refresh;

import java.time.Duration;
import java.util.UUID;

import com.loresentry.content.web.ContentFailure;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 최신화 입력을 S3 에 올린다. bucket 과 key 를 정하는 곳은 여기뿐이다.
 *
 * <p>key 는 {@code refresh/{project_id}/{request_id}/input.json} 이다. graph-rag 는 {@code refresh/} 아래만 입력으로
 * 받고, 요청 id 가 key 에 있어 다른 요청의 입력을 덮어쓰지 않는다. 그래도 덮어쓰지 않도록 S3 에 조건을 건다.
 */
@Component
class RefreshInputStorage {

    /** S3 응답이 늦을 때 요청 트랜잭션을 오래 붙잡지 않는다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final S3Client s3;

    private final String bucket;

    /** {@code s3} 는 최신화 bucket 리전으로 만든 전용 client 다(RefreshConfig). */
    RefreshInputStorage(@Qualifier("refreshS3Client") S3Client s3,
                        @Value("${graph-refresh.request-bucket:}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /** 올린 위치. 이벤트가 graph-rag 에 이 위치를 알려 준다. */
    record Location(String bucket, String key) {
    }

    /**
     * 입력을 올린다. 실패하면 {@code REFRESH_UNAVAILABLE} 이고, 요청 트랜잭션이 되돌려져 run 도 이벤트도 남지 않는다.
     * 반대로 올린 뒤 트랜잭션이 실패하면 객체만 남는데, bucket 의 수명 규칙과 정리 작업이 지운다.
     */
    Location put(UUID projectId, UUID requestId, byte[] json) {
        if (bucket.isBlank()) {
            throw new ContentFailure(ContentFailure.Reason.REFRESH_UNAVAILABLE);
        }
        String key = "refresh/" + projectId + "/" + requestId + "/input.json";
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .ifNoneMatch("*")
                            .contentType("application/json")
                            .overrideConfiguration(config -> config.apiCallTimeout(TIMEOUT))
                            .build(),
                    RequestBody.fromBytes(json));
        } catch (SdkException failure) {
            throw new ContentFailure(ContentFailure.Reason.REFRESH_UNAVAILABLE);
        }
        return new Location(bucket, key);
    }
}
