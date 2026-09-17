package com.loresentry.content.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class ImageServiceTest {

    private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final MediaProperties properties = new MediaProperties(
            "test-media-bucket",
            "ap-northeast-2",
            "https://media.test.invalid/",
            Duration.ofMinutes(5),
            10 * 1024 * 1024,
            List.of("image/png", "image/jpeg", "image/webp", "image/gif"));

    private final ImageRepository repository = mock(ImageRepository.class);

    private final S3Client s3Client = mock(S3Client.class);

    private S3Presigner presigner;

    private ImageService service;

    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("AKIATEST", "secret")))
                .build();
        service = new ImageService(repository, s3Client, presigner, properties);

        given(repository.insertPending(any(), any(), any(), any(), any(Long.class)))
                .willAnswer(invocation -> new ImageRecord(
                        UUID.randomUUID(),
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4),
                        ImageStatus.PENDING,
                        OffsetDateTime.now(),
                        null));
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void createUploadReturnsPresignedPutScopedToTheProject() {
        UploadTicket ticket = service.createUpload(PROJECT, "cover.png", "image/png", 1234L);

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
                .doesNotContainKey("host");
        assertThat(ticket.publicUrl()).isEqualTo("https://media.test.invalid/" + ticket.key());
        assertThat(ticket.expiresAt()).isAfter(java.time.Instant.now());

        verify(repository).insertPending(PROJECT, "cover.png", ticket.key(), "image/png", 1234L);
    }

    @Test
    void createUploadNormalisesContentTypeAndPicksExtensionFromIt() {
        UploadTicket ticket = service.createUpload(PROJECT, "photo.PNG", " IMAGE/JPEG ", 10L);

        assertThat(ticket.key()).endsWith(".jpg");
        assertThat(ticket.headers()).containsEntry("Content-Type", "image/jpeg");
    }

    @Test
    void createUploadRejectsDisallowedContentType() {
        assertThatExceptionOfType(InvalidUploadRequestException.class)
                .isThrownBy(() -> service.createUpload(PROJECT, "a.svg", "image/svg+xml", 10L));

        verify(repository, never()).insertPending(any(), any(), any(), any(), any(Long.class));
    }

    @Test
    void createUploadRejectsOversizedAndMissingSizes() {
        assertThatExceptionOfType(InvalidUploadRequestException.class)
                .isThrownBy(() -> service.createUpload(PROJECT, "a.png", "image/png", properties.maxSizeBytes() + 1));
        assertThatExceptionOfType(InvalidUploadRequestException.class)
                .isThrownBy(() -> service.createUpload(PROJECT, "a.png", "image/png", 0L));
        assertThatExceptionOfType(InvalidUploadRequestException.class)
                .isThrownBy(() -> service.createUpload(PROJECT, "a.png", "image/png", null));
    }

    @Test
    void createUploadRejectsMissingFileName() {
        assertThatExceptionOfType(InvalidUploadRequestException.class)
                .isThrownBy(() -> service.createUpload(PROJECT, "  ", "image/png", 10L));
    }

    @Test
    void completeMarksTheImageCommittedWhenTheObjectExistsWithTheDeclaredSize() {
        ImageRecord pending = pending(1234L);
        given(repository.find(PROJECT, pending.id())).willReturn(Optional.of(pending));
        given(s3Client.headObject(any(HeadObjectRequest.class)))
                .willReturn(HeadObjectResponse.builder().contentLength(1234L).contentType("image/png").build());
        given(repository.markCommitted(pending))
                .willReturn(new ImageRecord(pending.id(), PROJECT, pending.fileName(), pending.s3Key(),
                        pending.contentType(), pending.sizeBytes(), ImageStatus.COMMITTED,
                        pending.createdAt(), OffsetDateTime.now()));

        ImageRecord committed = service.complete(PROJECT, pending.id());

        assertThat(committed.status()).isEqualTo(ImageStatus.COMMITTED);
        assertThat(committed.committedAt()).isNotNull();
    }

    @Test
    void completeFailsWhenTheObjectWasNeverUploaded() {
        ImageRecord pending = pending(1234L);
        given(repository.find(PROJECT, pending.id())).willReturn(Optional.of(pending));
        given(s3Client.headObject(any(HeadObjectRequest.class)))
                .willThrow(NoSuchKeyException.builder().message("no such key").build());

        assertThatExceptionOfType(ObjectNotUploadedException.class)
                .isThrownBy(() -> service.complete(PROJECT, pending.id()));

        verify(repository, never()).markCommitted(any());
    }

    @Test
    void completeFailsWhenTheUploadedSizeDiffersFromTheDeclaredOne() {
        ImageRecord pending = pending(1234L);
        given(repository.find(PROJECT, pending.id())).willReturn(Optional.of(pending));
        given(s3Client.headObject(any(HeadObjectRequest.class)))
                .willReturn(HeadObjectResponse.builder().contentLength(99L).build());

        assertThatExceptionOfType(ObjectNotUploadedException.class)
                .isThrownBy(() -> service.complete(PROJECT, pending.id()));

        verify(repository, never()).markCommitted(any());
    }

    @Test
    void completeIsIdempotentForAnAlreadyCommittedImage() {
        ImageRecord committed = new ImageRecord(UUID.randomUUID(), PROJECT, "a.png", "projects/x/images/y.png",
                "image/png", 10L, ImageStatus.COMMITTED, OffsetDateTime.now(), OffsetDateTime.now());
        given(repository.find(PROJECT, committed.id())).willReturn(Optional.of(committed));

        assertThat(service.complete(PROJECT, committed.id())).isSameAs(committed);

        verify(s3Client, never()).headObject(any(HeadObjectRequest.class));
    }

    @Test
    void unknownImageIsReportedAsNotFound() {
        UUID missing = UUID.randomUUID();
        given(repository.find(PROJECT, missing)).willReturn(Optional.empty());

        assertThatExceptionOfType(ImageNotFoundException.class)
                .isThrownBy(() -> service.get(PROJECT, missing));
        assertThatExceptionOfType(ImageNotFoundException.class)
                .isThrownBy(() -> service.complete(PROJECT, missing));
    }

    private static ImageRecord pending(long size) {
        return new ImageRecord(UUID.randomUUID(), PROJECT, "cover.png",
                "projects/" + PROJECT + "/images/" + UUID.randomUUID() + ".png",
                "image/png", size, ImageStatus.PENDING, OffsetDateTime.now(), null);
    }
}
