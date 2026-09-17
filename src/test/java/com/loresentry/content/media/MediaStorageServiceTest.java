package com.loresentry.content.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class MediaStorageServiceTest {

    private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final MediaProperties properties = new MediaProperties(
            "test-media-bucket",
            "ap-northeast-2",
            "https://media.test.invalid/",
            Duration.ofMinutes(5),
            10 * 1024 * 1024,
            List.of("image/png", "image/jpeg", "image/webp", "image/gif"));

    private final S3Client s3Client = mock(S3Client.class);

    private S3Presigner presigner;

    private MediaStorageService service;

    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKIATEST", "secret")))
                .build();
        service = new MediaStorageService(s3Client, presigner, properties);
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void ticketIsAPresignedPutScopedToTheProject() {
        UploadTicket ticket = service.createImageUploadTicket(PROJECT, "image/png", 1234L);

        assertThat(ticket.method()).isEqualTo("PUT");
        assertThat(ticket.key())
                .startsWith("projects/" + PROJECT + "/images/")
                .endsWith(".png");
        assertThat(ticket.uploadUrl())
                .startsWith("https://test-media-bucket.s3.ap-northeast-2.amazonaws.com/" + ticket.key())
                .contains("X-Amz-Signature=")
                .contains("X-Amz-Expires=300");
        assertThat(ticket.headers())
                .containsEntry("Content-Type", "image/png")
                .containsEntry("Content-Length", "1234")
                .doesNotContainKey("Host");
        assertThat(ticket.publicUrl()).isEqualTo("https://media.test.invalid/" + ticket.key());
        assertThat(ticket.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void contentTypeIsNormalisedAndDecidesTheExtension() {
        UploadTicket ticket = service.createImageUploadTicket(PROJECT, " IMAGE/JPEG ", 10L);

        assertThat(ticket.key()).endsWith(".jpg");
        assertThat(ticket.headers()).containsEntry("Content-Type", "image/jpeg");
    }

    @Test
    void disallowedContentTypeIsRejected() {
        assertThatExceptionOfType(InvalidUploadRequestException.class)
                .isThrownBy(() -> service.createImageUploadTicket(PROJECT, "image/svg+xml", 10L));
        assertThatExceptionOfType(InvalidUploadRequestException.class)
                .isThrownBy(() -> service.createImageUploadTicket(PROJECT, null, 10L));
    }

    @Test
    void sizeOutsideTheLimitIsRejected() {
        assertThatExceptionOfType(InvalidUploadRequestException.class)
                .isThrownBy(() -> service.createImageUploadTicket(PROJECT, "image/png", properties.maxSizeBytes() + 1));
        assertThatExceptionOfType(InvalidUploadRequestException.class)
                .isThrownBy(() -> service.createImageUploadTicket(PROJECT, "image/png", 0L));
    }

    @Test
    void verifyUploadedReturnsTheObjectWhenSizeMatches() {
        given(s3Client.headObject(any(HeadObjectRequest.class)))
                .willReturn(HeadObjectResponse.builder().contentLength(1234L).contentType("image/png").build());

        StoredObject stored = service.verifyUploaded("projects/x/images/y.png", 1234L);

        assertThat(stored.sizeBytes()).isEqualTo(1234L);
        assertThat(stored.contentType()).isEqualTo("image/png");

        ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3Client).headObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("test-media-bucket");
        assertThat(request.getValue().key()).isEqualTo("projects/x/images/y.png");
    }

    @Test
    void verifyUploadedFailsWhenTheObjectIsMissing() {
        given(s3Client.headObject(any(HeadObjectRequest.class)))
                .willThrow(NoSuchKeyException.builder().message("no such key").build());

        assertThatExceptionOfType(ObjectNotUploadedException.class)
                .isThrownBy(() -> service.verifyUploaded("projects/x/images/y.png", 1234L));
    }

    @Test
    void verifyUploadedFailsWhenTheSizeDiffers() {
        given(s3Client.headObject(any(HeadObjectRequest.class)))
                .willReturn(HeadObjectResponse.builder().contentLength(99L).build());

        assertThatExceptionOfType(ObjectNotUploadedException.class)
                .isThrownBy(() -> service.verifyUploaded("projects/x/images/y.png", 1234L));
    }

    @Test
    void deleteTargetsTheConfiguredBucket() {
        service.delete("projects/x/images/y.png");

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("test-media-bucket");
        assertThat(request.getValue().key()).isEqualTo("projects/x/images/y.png");
    }
}
