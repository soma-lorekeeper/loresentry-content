package com.loresentry.content.image;

import java.util.UUID;

import com.loresentry.content.media.InvalidUploadRequestException;
import com.loresentry.content.media.MediaStorageService;
import com.loresentry.content.media.ObjectNotUploadedException;
import com.loresentry.content.media.UploadTicket;
import com.loresentry.content.project.ProjectActivity;
import com.loresentry.content.web.ContentFailure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브라우저가 S3 에 직접 올린다. 이 서비스는 "이 키에, 이 타입으로, 이 크기로, 5분 안에"라는 서명을
 * 발급하고, 나중에 그 객체가 실제로 올라왔는지 확인할 뿐이다(`IMAGE_UPLOAD_S3.md`).
 *
 * <p>인가 판단이 여기 있는 이유는 이미지가 프로젝트에 속하기 때문이다. gateway 는 BFF 라 AWS 자격
 * 증명을 가질 이유가 없다.
 */
@Service
public class ImageService {

    private final ImageRepository repository;

    private final MediaStorageService storage;

    private final ProjectActivity activity;

    public ImageService(ImageRepository repository, MediaStorageService storage,
            ProjectActivity activity) {
        this.repository = repository;
        this.storage = storage;
        this.activity = activity;
    }

    @Transactional
    public ImageDtos.Ticket createTicket(UUID ownerUserId, UUID projectId,
            ImageDtos.TicketRequest request) {
        requireProject(ownerUserId, projectId);
        if (request == null || request.contentType() == null || request.sizeBytes() == null) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }

        UploadTicket ticket;
        try {
            // 타입·크기 검증은 스토리지 계층이 한다. 서명에 들어가는 값이라 그쪽이 원천이다.
            ticket = storage.createImageUploadTicket(projectId, request.contentType(), request.sizeBytes());
        } catch (InvalidUploadRequestException invalid) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_UPLOAD_REQUEST);
        }

        ImageRow row = repository.insertPending(projectId, request.fileName(), ticket.key(),
                request.contentType().trim().toLowerCase(), request.sizeBytes());

        return new ImageDtos.Ticket(row.id(), ticket.key(), ticket.uploadUrl(), ticket.method(),
                ticket.headers(), ticket.expiresAt(), ticket.publicUrl());
    }

    /**
     * 브라우저가 업로드를 마쳤다고 알리는 지점이다. 그 말을 믿지 않고 {@code HeadObject} 로 객체가
     * 있는지, 선언한 크기와 같은지 확인한다 — 확인 없이 COMMITTED 로 두면 문서에 깨진 이미지 주소가 박힌다.
     *
     * <p>이미 COMMITTED 면 다시 확인하지 않고 현재 상태를 돌려준다. 응답을 받지 못한 클라이언트가
     * 다시 부를 수 있다.
     */
    @Transactional
    public ImageDtos.Image complete(UUID ownerUserId, UUID projectId, UUID imageId) {
        requireProject(ownerUserId, projectId);
        ImageRow row = require(projectId, imageId);
        if (row.isCommitted()) {
            return toDto(row);
        }

        try {
            storage.verifyUploaded(row.key(), row.sizeBytes());
        } catch (ObjectNotUploadedException notUploaded) {
            throw new ContentFailure(ContentFailure.Reason.OBJECT_NOT_UPLOADED);
        }

        ImageRow committed = repository.markCommitted(imageId);
        activity.touch(projectId);
        return toDto(committed);
    }

    @Transactional(readOnly = true)
    public ImageDtos.Image get(UUID ownerUserId, UUID projectId, UUID imageId) {
        requireProject(ownerUserId, projectId);
        return toDto(require(projectId, imageId));
    }

    private void requireProject(UUID ownerUserId, UUID projectId) {
        if (!repository.projectExists(ownerUserId, projectId)) {
            throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
        }
    }

    private ImageRow require(UUID projectId, UUID imageId) {
        return repository.find(projectId, imageId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.IMAGE_NOT_FOUND));
    }

    /**
     * {@code public_url} 은 COMMITTED 일 때만 준다. PENDING 인 객체의 주소를 미리 주면 클라이언트가
     * 그것을 문서에 넣고, 업로드가 끝내 실패하면 깨진 이미지가 남는다.
     */
    private ImageDtos.Image toDto(ImageRow row) {
        return new ImageDtos.Image(row.id(), row.projectId(), row.fileName(), row.key(),
                row.contentType(), row.sizeBytes(), row.status(),
                row.isCommitted() ? storage.publicUrl(row.key()) : null,
                row.createdAt(), row.committedAt());
    }
}
