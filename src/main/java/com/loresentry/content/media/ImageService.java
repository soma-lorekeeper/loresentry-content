package com.loresentry.content.media;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class ImageService {

    private static final int MAX_FILE_NAME_LENGTH = 255;

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/gif", "gif");

    private final ImageRepository repository;

    private final S3Client s3Client;

    private final S3Presigner presigner;

    private final MediaProperties properties;

    public ImageService(ImageRepository repository, S3Client s3Client, S3Presigner presigner, MediaProperties properties) {
        this.repository = repository;
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.properties = properties;
    }

    public UploadTicket createUpload(UUID projectId, String fileName, String contentType, Long sizeBytes) {
        String normalizedType = validateContentType(contentType);
        long size = validateSize(sizeBytes);
        String name = validateFileName(fileName);

        String key = "projects/" + projectId + "/images/" + UUID.randomUUID() + "." + EXTENSIONS.get(normalizedType);
        ImageRecord image = repository.insertPending(projectId, name, key, normalizedType, size);

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
                image.id(),
                key,
                presigned.url().toString(),
                presigned.httpRequest().method().name(),
                requiredHeaders(presigned),
                presigned.expiration(),
                publicUrl(key));
    }

    public ImageRecord complete(UUID projectId, UUID imageId) {
        ImageRecord image = get(projectId, imageId);
        if (image.status() == ImageStatus.COMMITTED) {
            return image;
        }

        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(image.s3Key())
                    .build());
        } catch (NoSuchKeyException exception) {
            throw new ObjectNotUploadedException("object " + image.s3Key() + " has not been uploaded");
        }

        if (head.contentLength() == null || head.contentLength() != image.sizeBytes()) {
            throw new ObjectNotUploadedException("object " + image.s3Key() + " size " + head.contentLength()
                    + " does not match the declared size " + image.sizeBytes());
        }

        return repository.markCommitted(image);
    }

    public ImageRecord get(UUID projectId, UUID imageId) {
        return repository.find(projectId, imageId)
                .orElseThrow(() -> new ImageNotFoundException(projectId, imageId));
    }

    public String publicUrl(String key) {
        String base = properties.publicBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + key;
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

    private long validateSize(Long sizeBytes) {
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new InvalidUploadRequestException("sizeBytes must be a positive number");
        }
        if (sizeBytes > properties.maxSizeBytes()) {
            throw new InvalidUploadRequestException("sizeBytes " + sizeBytes + " exceeds the limit of "
                    + properties.maxSizeBytes() + " bytes");
        }
        return sizeBytes;
    }

    private String validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new InvalidUploadRequestException("fileName is required");
        }
        String trimmed = fileName.trim();
        if (trimmed.length() > MAX_FILE_NAME_LENGTH) {
            throw new InvalidUploadRequestException("fileName must be at most " + MAX_FILE_NAME_LENGTH + " characters");
        }
        return trimmed;
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

    Duration uploadUrlTtl() {
        return properties.uploadUrlTtl();
    }
}
