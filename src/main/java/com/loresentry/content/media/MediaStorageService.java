package com.loresentry.content.media;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
        } catch (NoSuchKeyException exception) {
            throw new ObjectNotUploadedException("object " + key + " has not been uploaded");
        } catch (S3Exception exception) {
            // HeadObject 는 본문 없는 404 를 돌려주므로 SDK 가 NoSuchKeyException 으로 매핑하지
            // 못하고 평범한 S3Exception 을 던진다. 그래서 위의 catch 만으로는 없는 객체를 잡지 못한다.
            if (exception.statusCode() == 404) {
                throw new ObjectNotUploadedException("object " + key + " has not been uploaded");
            }
            throw exception;
        }

        if (head.contentLength() == null || head.contentLength() != expectedSizeBytes) {
            throw new ObjectNotUploadedException("object " + key + " size " + head.contentLength()
                    + " does not match the declared size " + expectedSizeBytes);
        }

        return new StoredObject(key, head.contentType(), head.contentLength());
    }

    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .build());
    }

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
