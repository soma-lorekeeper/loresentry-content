package com.loresentry.content.document;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.project.ProjectActivity;
import com.loresentry.content.web.ContentFailure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {

    /** 요구사항 §3.5. 자동 버전은 이 간격보다 자주 남기지 않는다. */
    static final Duration AUTO_VERSION_INTERVAL = Duration.ofMinutes(5);

    /** 요구사항 §3.5. 자동 버전의 보관 기간. 이름 붙인 버전은 영구 보관한다. */
    static final Duration AUTO_VERSION_RETENTION = Duration.ofDays(30);

    static final int BODY_MAX = 1_000_000;

    static final int TITLE_MAX = 255;

    private final DocumentRepository repository;

    private final ProjectActivity activity;

    public DocumentService(DocumentRepository repository, ProjectActivity activity) {
        this.repository = repository;
        this.activity = activity;
    }

    /** 휴지통 문서는 목록에만 보이고 열리지 않는다. 그래서 여기도 {@link #requireHeader}를 지난다. */
    @Transactional(readOnly = true)
    public DocumentResponses.Content get(UUID ownerUserId, UUID fileId) {
        return repository.contentOf(requireHeader(ownerUserId, fileId));
    }

    /**
     * 조건부 저장이다. {@code expectedRevision}이 어긋나면 덮어쓰지 않고 현재 문서와 공통 조상을
     * 돌려준다. 같은 {@code saveId}가 다시 오면 revision 을 또 올리지 않는다 — 응답을 받지 못한
     * 클라이언트의 재시도이므로, 두 번 적용하면 같은 내용의 revision 이 두 개 생긴다.
     */
    @Transactional
    public DocumentResponses.Content save(UUID ownerUserId, UUID fileId, long expectedRevision,
            UUID saveId, DocumentSnapshot incoming) {
        DocumentRepository.Header header = requireHeader(ownerUserId, fileId);
        if (header.locked()) {
            throw new ContentFailure(ContentFailure.Reason.DOCUMENT_LOCKED);
        }

        if (saveId != null && saveId.equals(header.lastSaveId())) {
            return repository.contentOf(header);
        }

        DocumentSnapshot snapshot = validate(header.projectId(), fileId, incoming);

        boolean applied;
        try {
            applied = repository.updateIfRevisionMatches(fileId, expectedRevision, snapshot, saveId);
        } catch (DuplicateKeyException exception) {
            throw new ContentFailure(ContentFailure.Reason.FILE_TITLE_TAKEN);
        }

        if (!applied) {
            DocumentResponses.Content current = repository.find(ownerUserId, fileId)
                    .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.FILE_NOT_FOUND));
            throw new DocumentConflict(current,
                    repository.snapshotAtRevision(fileId, expectedRevision).orElse(null));
        }

        repository.replaceProperties(fileId, snapshot.properties());
        repository.replaceRelations(fileId, snapshot.relations());

        DocumentResponses.Content saved = repository.find(ownerUserId, fileId).orElseThrow();
        recordAutoVersion(fileId, saved);
        // 문서를 쓴 것이 곧 프로젝트를 작업한 것이다. 같은 트랜잭션에서 올린다.
        activity.touch(header.projectId());
        return saved;
    }

    @Transactional
    public DocumentResponses.Content setLocked(UUID ownerUserId, UUID fileId, boolean locked) {
        DocumentRepository.Header header = requireHeader(ownerUserId, fileId);
        repository.setLocked(fileId, locked);
        activity.touch(header.projectId());
        return repository.find(ownerUserId, fileId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public DocumentResponses.VersionList versions(UUID ownerUserId, UUID fileId) {
        requireHeader(ownerUserId, fileId);
        return new DocumentResponses.VersionList(repository.versions(fileId));
    }

    /** 사용자가 이름을 붙인 버전은 파일이 영구 삭제될 때까지 남는다 — {@code expires_at}이 없다. */
    @Transactional
    public DocumentResponses.Version saveNamed(UUID ownerUserId, UUID fileId, String label) {
        DocumentResponses.Content current = get(ownerUserId, fileId);
        UUID versionId = repository.insertVersion(fileId, current.revisionNo(), "NAMED",
                label == null || label.isBlank() ? null : label.trim(), current.snapshot(), null);
        return repository.findVersion(fileId, versionId).orElseThrow();
    }

    /**
     * 복원은 문서를 되감는 것이 아니라 <b>새 변경으로 저장</b>한다(요구사항 §3.5). 되감으면 복원
     * 자체가 기록에서 사라져 무엇이 언제 바뀌었는지 설명할 수 없다.
     *
     * <p>복원 직전 상태를 먼저 버전으로 남긴다. 복원이 잘못된 선택이었을 때 돌아올 자리가 필요하다.
     */
    @Transactional
    public DocumentResponses.Content restore(UUID ownerUserId, UUID fileId, UUID versionId,
            long expectedRevision) {
        DocumentRepository.Header header = requireHeader(ownerUserId, fileId);
        if (header.locked()) {
            throw new ContentFailure(ContentFailure.Reason.DOCUMENT_LOCKED);
        }

        DocumentResponses.Version version = repository.findVersion(fileId, versionId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.VERSION_NOT_FOUND));

        DocumentResponses.Content current = repository.contentOf(header);
        repository.insertVersion(fileId, current.revisionNo(), "RESTORE", "복원 전", current.snapshot(), null);

        DocumentSnapshot snapshot = validate(header.projectId(), fileId, version.snapshot());
        boolean applied;
        try {
            applied = repository.updateIfRevisionMatches(fileId, expectedRevision, snapshot, null);
        } catch (DuplicateKeyException exception) {
            throw new ContentFailure(ContentFailure.Reason.FILE_TITLE_TAKEN);
        }
        if (!applied) {
            throw new DocumentConflict(current,
                    repository.snapshotAtRevision(fileId, expectedRevision).orElse(null));
        }

        repository.replaceProperties(fileId, snapshot.properties());
        repository.replaceRelations(fileId, snapshot.relations());
        activity.touch(header.projectId());
        return repository.find(ownerUserId, fileId).orElseThrow();
    }

    @Transactional
    public void deleteVersion(UUID ownerUserId, UUID fileId, UUID versionId) {
        requireHeader(ownerUserId, fileId);
        DocumentResponses.Version version = repository.findVersion(fileId, versionId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.VERSION_NOT_FOUND));
        repository.deleteVersion(version.id());
    }

    private void recordAutoVersion(UUID fileId, DocumentResponses.Content saved) {
        OffsetDateTime last = repository.lastAutoVersionAt(fileId).orElse(null);
        if (last != null && last.isAfter(OffsetDateTime.now().minus(AUTO_VERSION_INTERVAL))) {
            return;
        }
        repository.insertVersion(fileId, saved.revisionNo(), "AUTO", null, saved.snapshot(),
                OffsetDateTime.now().plus(AUTO_VERSION_RETENTION));
    }

    private DocumentRepository.Header requireHeader(UUID ownerUserId, UUID fileId) {
        DocumentRepository.Header header = repository.findHeader(ownerUserId, fileId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.FILE_NOT_FOUND));
        if (header.trashedAt() != null) {
            throw new ContentFailure(ContentFailure.Reason.FILE_NOT_FOUND);
        }
        return header;
    }

    private DocumentSnapshot validate(UUID projectId, UUID fileId, DocumentSnapshot incoming) {
        if (incoming == null) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }

        String title = incoming.title() == null ? "" : incoming.title().trim();
        if (title.isEmpty() || title.length() > TITLE_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_FILE_TITLE);
        }

        String body = incoming.bodyMd() == null ? "" : incoming.bodyMd();
        if (body.length() > BODY_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }

        List<DocumentSnapshot.TextProperty> properties =
                incoming.properties() == null ? List.of() : incoming.properties();
        for (DocumentSnapshot.TextProperty property : properties) {
            if (property.key() == null || property.key().isBlank() || property.value() == null) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
            }
        }

        List<DocumentSnapshot.Relation> relations =
                incoming.relations() == null ? List.of() : incoming.relations();
        for (DocumentSnapshot.Relation relation : relations) {
            if (relation.relationKey() == null || relation.relationKey().isBlank()
                    || relation.targetDocumentId() == null) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
            }
            // 자기 참조는 그래프에 자기 루프를 만들고 타임라인에서 의미가 없다.
            if (relation.targetDocumentId().equals(fileId)
                    || !repository.isUsableRelationTarget(projectId, relation.targetDocumentId())) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_RELATION_TARGET);
            }
        }

        return new DocumentSnapshot(title, body, properties, relations);
    }
}
