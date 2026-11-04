package com.loresentry.content.media;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.logging.LogFlow;
import com.loresentry.content.logging.SkipEntryLog;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class MediaStorageService {

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif");

    private final S3Client s3Client;

    private final S3Presigner presigner;

    private final MediaProperties properties;

    public MediaStorageService(S3Client s3Client, S3Presigner presigner, MediaProperties properties) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.properties = properties;
    }

    public UploadTicket createImageUploadTicket(UUID projectId, String contentType, long sizeBytes) {
        // MIME 타입은 받는 목록(EXTENSIONS) 안이면 그대로, 밖이면 other 로 남는다. 객체 키·서명 URL 은 남기지 않는다
        AppLogger.current().info("image upload ticket sign", LogField.projectId(projectId),
                LogField.code("content_type", contentType, EXTENSIONS.keySet()), LogField.param("size_bytes", sizeBytes));
        String normalizedType = validateContentType(contentType);
        long size = validateSize(sizeBytes);
        String key = imageKey(projectId, normalizedType);

        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(normalizedType)
                .contentLength(size)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(properties.uploadUrlTtl())
                .putObjectRequest(putObject)
                .build());

        return new UploadTicket(
                key,
                presigned.url().toString(),
                presigned.httpRequest().method().name(),
                requiredHeaders(presigned),
                presigned.expiration(),
                publicUrl(key));
    }

    public StoredObject verifyUploaded(String key, long expectedSizeBytes) {
        // 객체 키에는 프로젝트 id 가 들어 있어 남기지 않는다(호출한 쪽 줄에 image_id 가 있다)
        AppLogger.current().info("image object verify", LogField.param("expected_size_bytes", expectedSizeBytes));
        HeadObjectResponse head;
        try {
            // S3 호출 하나. 결과 줄(s3 call completed·failed, 걸린 시간, 실패면 예외 종류·응답 코드)은 LogFlow 가 남긴다
            head = LogFlow.call("s3", call -> s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build()));
        } catch (NoSuchKeyException exception) {
            AppLogger.current().warn("image object not uploaded", LogField.errorType("OBJECT_NOT_UPLOADED"));
            throw new ObjectNotUploadedException("object " + key + " has not been uploaded");
        } catch (S3Exception exception) {
            // HeadObject 는 본문 없는 404 를 돌려주므로 SDK 가 NoSuchKeyException 으로 매핑하지
            // 못하고 평범한 S3Exception 을 던진다. 위의 catch 만으로는 없는 객체를 잡지 못한다.
            //
            // 403 도 같이 본다. 호출자에게 s3:ListBucket 이 없으면 S3 는 "없는 객체"를 404 대신
            // 403 으로 감춘다 — 버킷에 무엇이 있는지 알려 주지 않기 위해서다. 정책에 ListBucket 을
            // 넣어 404 를 받게 했지만(docs/aws/iam-content-media-policy.json), 정책이 아직 반영되지
            // 않은 환경에서도 이 경로가 500 이 되지 않아야 한다.
            if (exception.statusCode() == 404 || exception.statusCode() == 403) {
                // 응답 코드(404·403)는 바로 앞의 s3 call failed 줄에 있다
                AppLogger.current().warn("image object not uploaded", LogField.errorType("OBJECT_NOT_UPLOADED"));
                throw new ObjectNotUploadedException("object " + key + " has not been uploaded");
            }
            // 그 밖의 S3 오류는 500 으로 나간다. 예외 종류와 응답 코드는 s3 call failed 줄에 있다(메시지에는 키가 들어 있어 남기지 않는다)
            throw exception;
        }

        if (head.contentLength() == null || head.contentLength() != expectedSizeBytes) {
            // 브라우저가 선언한 크기와 실제로 올라온 객체의 크기가 다르다
            AppLogger.current().warn("image object size mismatch", LogField.errorType("OBJECT_NOT_UPLOADED"),
                    LogField.param("expected_size_bytes", expectedSizeBytes), LogField.result("size_bytes", head.contentLength()));
            throw new ObjectNotUploadedException("object " + key + " size " + head.contentLength()
                    + " does not match the declared size " + expectedSizeBytes);
        }

        return new StoredObject(key, head.contentType(), head.contentLength());
    }

    public void delete(String key) {
        // 객체 키에는 프로젝트 id 가 들어 있어 남기지 않는다
        AppLogger.current().info("media object delete");
        // S3 호출 하나. 결과 줄(s3 call completed·failed)은 LogFlow 가 남긴다. 실패하면 예외가 그대로 나간다
        LogFlow.call("s3", call -> s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build()));
    }

    @SkipEntryLog("Called once per document with an image while building file tree and graph responses. It only builds a URL, so a line adds nothing to the flow")
    public String publicUrl(String key) {
        String base = properties.publicBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + key;
    }

    static String imageKey(UUID projectId, String contentType) {
        return "projects/" + projectId + "/images/" + UUID.randomUUID() + "." + EXTENSIONS.get(contentType);
    }

    private String validateContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new InvalidUploadRequestException("contentType is required");
        }
        String normalized = contentType.trim().toLowerCase();
        if (!properties.allowedContentTypes().contains(normalized) || !EXTENSIONS.containsKey(normalized)) {
            throw new InvalidUploadRequestException("contentType " + contentType + " is not allowed; allowed: "
                    + properties.allowedContentTypes());
        }
        return normalized;
    }

    private long validateSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new InvalidUploadRequestException("sizeBytes must be a positive number");
        }
        if (sizeBytes > properties.maxSizeBytes()) {
            throw new InvalidUploadRequestException("sizeBytes " + sizeBytes + " exceeds the limit of "
                    + properties.maxSizeBytes() + " bytes");
        }
        return sizeBytes;
    }

    private static Map<String, String> requiredHeaders(PresignedPutObjectRequest presigned) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : presigned.signedHeaders().entrySet()) {
            if (entry.getKey().equalsIgnoreCase("host")) {
                continue;
            }
            headers.put(canonical(entry.getKey()), String.join(",", entry.getValue()));
        }
        return headers;
    }

    private static String canonical(String header) {
        StringBuilder out = new StringBuilder(header.length());
        boolean upper = true;
        for (char c : header.toCharArray()) {
            out.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
            upper = c == '-';
        }
        return out.toString();
    }
}
