package com.loresentry.content.refresh;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Duration;
import java.util.UUID;

/** Private refresh bucket only; never accepts arbitrary bucket/key access. */
@Component
public class RefreshObjects {
    private final S3Client s3;
    private final String bucket;

    public RefreshObjects(S3Client s3, @Value("${graph-refresh.bucket:}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public String bucket() {
        if (bucket.isBlank()) throw new IllegalStateException("Refresh bucket is not configured");
        return bucket;
    }

    public void put(String key, byte[] body) {
        String checksum;
        try {
            checksum =
                    java.util.Base64.getEncoder()
                            .encodeToString(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(body));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket())
                        .key(key)
                        .ifNoneMatch("*")
                        .checksumSHA256(checksum)
                        .contentType("application/json")
                        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15)))
                        .build(),
                RequestBody.fromBytes(body));
    }

    public void deleteProjectAfterCommit(UUID project) {
        if (bucket.isBlank()) return;
        afterCommit(
                () -> {
                    String continuation = null;
                    do {
                        var page =
                                s3.listObjectsV2(
                                        ListObjectsV2Request.builder()
                                                .bucket(bucket)
                                                .prefix("refresh/" + project + "/")
                                                .continuationToken(continuation)
                                                .overrideConfiguration(
                                                        c ->
                                                                c.apiCallTimeout(
                                                                        Duration.ofSeconds(15)))
                                                .build());
                        for (var object : page.contents()) {
                            s3.deleteObject(
                                    DeleteObjectRequest.builder()
                                            .bucket(bucket)
                                            .key(object.key())
                                            .overrideConfiguration(
                                                    c -> c.apiCallTimeout(Duration.ofSeconds(15)))
                                            .build());
                        }
                        continuation = page.nextContinuationToken();
                    } while (continuation != null);
                });
    }

    public void discardAfterCommit(GraphRefreshCompletedEvent event) {
        if (bucket.isBlank()
                || event.result() == null
                || !bucket.equals(event.result().bucket())
                || !("refresh/" + event.projectId() + "/" + event.requestId() + "/result.json")
                        .equals(event.result().key())) return;
        afterCommit(
                () ->
                        s3.deleteObject(
                                DeleteObjectRequest.builder()
                                        .bucket(bucket)
                                        .key(event.result().key())
                                        .overrideConfiguration(
                                                c -> c.apiCallTimeout(Duration.ofSeconds(15)))
                                        .build()));
    }

    private void afterCommit(Runnable cleanup) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            cleanup.run();
                        } catch (RuntimeException failure) {
                            LoggerFactory.getLogger(RefreshObjects.class)
                                    .error(
                                            "Refresh artifact cleanup failed: reason={}",
                                            failure.getClass().getName());
                        }
                    }
                });
    }

    public byte[] result(String project, String request, String suppliedBucket, String key) {
        if (!bucket().equals(suppliedBucket)
                || !key.equals("refresh/" + project + "/" + request + "/result.json"))
            throw new IllegalArgumentException("Invalid refresh result location");
        try (var stream =
                s3.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucket())
                                .key(key)
                                .overrideConfiguration(
                                        c -> c.apiCallTimeout(Duration.ofSeconds(15)))
                                .build())) {
            byte[] bytes = stream.readNBytes(16 * 1024 * 1024 + 1);
            if (bytes.length > 16 * 1024 * 1024)
                throw new IllegalArgumentException("Refresh result too large");
            return bytes;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Refresh result read failed");
        }
    }
}
