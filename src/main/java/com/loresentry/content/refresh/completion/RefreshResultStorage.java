package com.loresentry.content.refresh.completion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * graph-rag 가 쓴 최신화 결과 파일을 S3 에서 읽는다. 위치(bucket, key)는 완료 이벤트가 알려 준 값을 그대로 쓴다 —
 * key 를 해석하거나 다시 만들지 않는다(graph-rag 계약).
 *
 * <p>파일을 쓸 수 없는 경우는 {@link Unreadable} 로 알린다. 다시 시도해도 같은 결과인 경우들이다. 그 밖의 S3 오류
 * (연결 실패, 시간 초과 등)는 그대로 던져 Kafka 재시도에 맡긴다.
 */
@Component
class RefreshResultStorage {

    /** 결과 파일은 최대 16MiB 다. 그만큼을 받을 시간을 둔다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final S3Client s3;

    /** {@code s3} 는 최신화 bucket 리전으로 만든 전용 client 다(RefreshConfig). */
    RefreshResultStorage(@Qualifier("refreshS3Client") S3Client s3) {
        this.s3 = s3;
    }

    /** 읽은 파일. {@code sha256} 은 바이트를 직접 계산한 값이고, graph-rag 가 적은 메타데이터와 같다. */
    record Stored(byte[] bytes, String sha256) {
    }

    /** 결과 파일을 쓸 수 없다. {@code code} 는 run 의 실패 코드가 된다. */
    static final class Unreadable extends RuntimeException {

        private final String code;

        Unreadable(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    /**
     * 결과 파일 전체를 읽고, graph-rag 가 객체 메타데이터({@code sha256})에 적은 해시와 맞는지 확인한다.
     *
     * @param maxBytes 받을 수 있는 최대 크기. 넘으면 끝까지 받지 않고 끊는다
     */
    Stored read(String bucket, String key, long maxBytes) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .overrideConfiguration(config -> config.apiCallTimeout(TIMEOUT))
                .build();
        try (ResponseInputStream<GetObjectResponse> object = s3.getObject(request)) {
            Long length = object.response().contentLength();
            if (length != null && length > maxBytes) {
                object.abort();
                throw new Unreadable("RESULT_TOO_LARGE", "Result object exceeds the size limit");
            }
            byte[] bytes = object.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (bytes.length > maxBytes) {
                object.abort();
                throw new Unreadable("RESULT_TOO_LARGE", "Result object exceeds the size limit");
            }
            String actual = sha256Hex(bytes);
            String expected = object.response().metadata() == null ? null : object.response().metadata().get("sha256");
            if (expected == null || !expected.equalsIgnoreCase(actual)) {
                throw new Unreadable("RESULT_INVALID", "Result object does not match its sha256 metadata");
            }
            return new Stored(bytes, actual);
        } catch (NoSuchKeyException missing) {
            // 결과 객체는 며칠 뒤 만료된다(bucket 수명 규칙). DLQ 를 늦게 다시 보내면 생긴다.
            throw new Unreadable("RESULT_EXPIRED", "Result object is gone");
        } catch (IOException reading) {
            throw new UncheckedIOException(reading);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
