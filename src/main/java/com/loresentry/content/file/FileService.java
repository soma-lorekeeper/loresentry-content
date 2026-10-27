package com.loresentry.content.file;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.loresentry.content.event.FileChanged.ChangeType;
import com.loresentry.content.event.FileChanged.Field;
import com.loresentry.content.event.ProjectChangedMessages;
import com.loresentry.content.media.MediaCleanup;
import com.loresentry.content.media.MediaStorageService;
import com.loresentry.content.outbox.OutboxRecord;
import com.loresentry.content.outbox.OutboxRepository;
import com.loresentry.content.project.ProjectActivity;
import com.loresentry.content.web.ContentFailure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileService {

    static final int TITLE_MAX = 255;

    /** {@code base_folders} 시드의 원고 폴더. 에피소드가 붙을 수 있는 유일한 분류다. */
    private static final String MANUSCRIPT = "MANUSCRIPT";

    private final FileRepository repository;

    private final ProjectActivity activity;

    /**
     * 문서가 바뀌면 메서드 마지막에 {@code outbox.append(messages.xxx(...))} 를 한 번 부른다. 무엇이 바뀌었는지는
     * 쓰기 SQL 이 돌려준 결과로 정한다. 이벤트를 어떻게 쓰고 순서를 어떻게 지키는지는 OutboxRepository 가 맡는다.
     */
    private final OutboxRepository outbox;

    private final ProjectChangedMessages messages;

    private final MediaStorageService storage;

    private final MediaCleanup mediaCleanup;

    public FileService(FileRepository repository, ProjectActivity activity, OutboxRepository outbox,
            ProjectChangedMessages messages, MediaStorageService storage, MediaCleanup mediaCleanup) {
        this.repository = repository;
        this.activity = activity;
        this.outbox = outbox;
        this.messages = messages;
        this.storage = storage;
        this.mediaCleanup = mediaCleanup;
    }

    @Transactional(readOnly = true)
    public FileResponses.Tree tree(UUID ownerUserId, UUID projectId) {
        requireProject(ownerUserId, projectId);
        return FileResponses.tree(repository.baseFolders(), repository.episodes(projectId),
                repository.activeDocuments(projectId), storage::publicUrl);
    }

    @Transactional(readOnly = true)
    public FileResponses.TrashList trash(UUID ownerUserId, UUID projectId) {
        requireProject(ownerUserId, projectId);
        return FileResponses.trash(repository.trashedDocuments(projectId));
    }

    @Transactional
    public Object create(UUID ownerUserId, UUID projectId, FileRequests.Create request) {
        requireProject(ownerUserId, projectId);
        String title = validateTitle(request.title());
        String kind = request.kind() == null ? "" : request.kind();

        Object created = switch (kind) {
            case "document" -> document(createDocument(projectId, request, title));
            case "episode" -> createEpisode(ownerUserId, projectId, title);
            default -> throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        };
        activity.touch(projectId);
        // 에피소드는 문서 스냅샷이 아니다. 문서는 episode_id 만 가진다.
        if (created instanceof FileResponses.Document document) {
            outbox.append(messages.fileChanged(projectId, document.id(), ChangeType.CREATED,
                    EnumSet.allOf(Field.class)));
        }
        return created;
    }

    /**
     * 그래프 최신화 확정이 새 설정 문서를 만든다. 자리는 그 분류 폴더의 맨 끝이고, 본문은 비어 있다(확정이 곧 채운다).
     *
     * <p>{@link #create} 와 달리 이벤트를 쓰지 않는다 — 확정은 여러 문서를 바꾸고 트랜잭션 끝에서 outbox 를 한 번만
     * 쓴다({@code OutboxRepository#append} 의 규칙). 그래서 호출자의 트랜잭션 안에서만 돈다.
     *
     * @return 만든 문서의 id. 같은 자리에 같은 제목이 있으면 {@code FILE_TITLE_TAKEN}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID createSettingDocument(UUID projectId, String folderCode, String title) {
        if ("MANUSCRIPT".equals(folderCode)) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_FILE_LOCATION);
        }
        String validTitle = validateTitle(title);
        short folderId = folderIdOf(folderCode);
        List<String> ranks = repository.siblingRanks(projectId, folderId, null, null);
        String rank = Ranks.between(ranks.isEmpty() ? null : ranks.getLast(), null);
        return duplicateAware(() -> repository.insertDocument(projectId, folderId, null, validTitle, rank)).id();
    }

    private FileRows.Document createDocument(UUID projectId, FileRequests.Create request, String title) {
        short folderId = folderIdOf(request.folderCode());
        UUID episodeId = validateEpisode(projectId, request.folderCode(), request.episodeId());

        List<String> ranks = repository.siblingRanks(projectId, folderId, episodeId, null);
        String rank = Ranks.between(ranks.isEmpty() ? null : ranks.getLast(), null);

        return duplicateAware(() -> repository.insertDocument(projectId, folderId, episodeId, title, rank));
    }

    private FileResponses.Episode createEpisode(UUID ownerUserId, UUID projectId, String name) {
        List<String> ranks = repository.episodeRanks(projectId);
        String rank = Ranks.between(ranks.isEmpty() ? null : ranks.getLast(), null);

        return FileResponses.Episode.from(duplicateAwareEpisode(
                () -> repository.insertEpisode(projectId, ownerUserId, name, rank)));
    }

    @Transactional
    public FileResponses.Document rename(UUID ownerUserId, UUID fileId, FileRequests.Rename request) {
        FileRows.Document document = requireDocument(ownerUserId, fileId);
        requireEditable(document);
        String title = validateTitle(request.title());

        FileResponses.Document renamed = document(
                duplicateAware(() -> repository.renameDocument(fileId, title)));
        activity.touch(document.projectId());
        outbox.append(messages.fileChanged(document.projectId(), fileId, ChangeType.UPDATED,
                Set.of(Field.TITLE)));
        return renamed;
    }

    /** 에피소드 이름은 어느 문서의 스냅샷에도 없으므로 이벤트가 없다. */
    @Transactional
    public FileResponses.Episode renameEpisode(UUID ownerUserId, UUID episodeId, FileRequests.Rename request) {
        requireEpisode(ownerUserId, episodeId);
        String name = validateTitle(request.title());

        FileResponses.Episode renamed = FileResponses.Episode.from(
                duplicateAwareEpisode(() -> repository.renameEpisode(episodeId, name)));
        activity.touch(repository.projectOfEpisodeOrNull(episodeId));
        return renamed;
    }

    /**
     * 순서 값은 <b>서버가</b> 이웃에서 계산한다. 클라이언트가 계산해 보내면 두 클라이언트가 같은 값을
     * 만들 수 있고, 목록을 언제 읽었는지에 따라 결과가 달라진다.
     */
    @Transactional
    public FileResponses.Document move(UUID ownerUserId, UUID fileId, FileRequests.Move request) {
        FileRows.Document document = requireDocument(ownerUserId, fileId);
        requireEditable(document);

        String folderCode = request.folderCode() == null ? document.folderCode() : request.folderCode();
        short folderId = folderIdOf(folderCode);
        UUID episodeId = validateEpisode(document.projectId(), folderCode, request.episodeId());

        List<String> siblings = repository.siblingRanks(document.projectId(), folderId, episodeId, fileId);
        String rank = rankFor(document.projectId(), folderId, episodeId, request.beforeFileId(), siblings);

        FileRows.Placed moved = duplicateAware(() -> repository.moveDocument(fileId, folderId, episodeId, rank));
        activity.touch(document.projectId());
        // 같은 자리 안에서 순서만 바꾼 이동은 스냅샷이 그대로다(rank 는 싣지 않는다).
        if (moved.locationChanged()) {
            outbox.append(messages.fileChanged(document.projectId(), fileId, ChangeType.UPDATED,
                    Set.of(Field.FOLDER)));
        }
        return document(moved.document());
    }

    private String rankFor(UUID projectId, short folderId, UUID episodeId, UUID beforeFileId,
            List<String> siblings) {
        if (beforeFileId == null) {
            return Ranks.between(siblings.isEmpty() ? null : siblings.getLast(), null);
        }

        String next = repository.rankOfSibling(projectId, folderId, episodeId, beforeFileId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.INVALID_FILE_LOCATION));

        String previous = null;
        for (String sibling : siblings) {
            if (sibling.compareTo(next) >= 0) {
                break;
            }
            previous = sibling;
        }
        return Ranks.between(previous, next);
    }

    @Transactional
    public void moveToTrash(UUID ownerUserId, UUID fileId) {
        FileRows.Document document = requireDocument(ownerUserId, fileId);
        requireEditable(document);
        boolean trashed = repository.trashDocument(fileId);
        activity.touch(document.projectId());
        // 관계 행은 남는다. 이웃 문서의 스냅샷은 그대로이므로 이 문서의 이벤트 하나면 된다.
        if (trashed) {
            outbox.append(messages.fileChanged(document.projectId(), fileId, ChangeType.TRASHED,
                    Set.of(Field.STATE)));
        }
    }

    @Transactional
    public FileResponses.Document restore(UUID ownerUserId, UUID fileId) {
        FileRows.Document document = requireDocument(ownerUserId, fileId);
        if (!document.isTrashed()) {
            return document(document);
        }

        // 원래 있던 에피소드가 사라졌으면 원고 폴더 최상위로 돌린다(요구사항 §4.2).
        boolean dropEpisode = document.episodeId() != null
                && !repository.episodeStillExists(document.episodeId());
        UUID episodeId = dropEpisode ? null : document.episodeId();

        List<String> siblings = repository.siblingRanks(
                document.projectId(), document.folderId(), episodeId, fileId);
        String rank = Ranks.between(siblings.isEmpty() ? null : siblings.getLast(), null);

        FileRows.Placed restored = duplicateAware(() -> repository.restoreDocument(fileId, dropEpisode, rank));
        activity.touch(document.projectId());
        Set<Field> changed = restored.locationChanged()
                ? EnumSet.of(Field.STATE, Field.FOLDER)
                : EnumSet.of(Field.STATE);
        outbox.append(messages.fileChanged(document.projectId(), fileId, ChangeType.RESTORED, changed));
        return document(restored.document());
    }

    /**
     * 이 문서가 가진 관계(이 문서가 low)는 DELETED 와 함께 사라진다. 이 문서가 high 인 관계는 상대(low) 문서의
     * 것이므로, 그 문서들의 관계가 바뀐 것으로 알린다.
     */
    @Transactional
    public void deletePermanently(UUID ownerUserId, UUID fileId) {
        FileRows.Document document = requireDocument(ownerUserId, fileId);
        if (!document.isTrashed()) {
            throw new ContentFailure(ContentFailure.Reason.FILE_NOT_TRASHED);
        }
        // 잠근 뒤에 읽어야, 읽은 뒤 새로 걸린 관계가 삭제에 휩쓸려 알림 없이 사라지지 않는다.
        repository.lockForDelete(fileId);
        List<UUID> owners = repository.relationOwnersOf(fileId);
        Optional<UUID> image = repository.imageOf(fileId);
        repository.deleteDocument(fileId);
        image.flatMap(repository::deleteImage).ifPresent(key -> mediaCleanup.deleteAfterCommit(List.of(key)));
        activity.touch(document.projectId());

        List<OutboxRecord> changes = new ArrayList<>();
        changes.add(messages.fileDeleted(document.projectId(), fileId));
        owners.forEach(owner -> changes.add(messages.fileChanged(document.projectId(), owner,
                ChangeType.UPDATED, Set.of(Field.RELATIONS))));
        outbox.append(changes.toArray(OutboxRecord[]::new));
    }

    /**
     * 문서의 대표 이미지를 정한다. 이미지는 먼저 {@code POST /projects/{id}/images} 로 올리고 확인까지 마친 것이어야
     * 한다.
     *
     * <p>이전 이미지는 행과 S3 객체를 함께 지운다. 이미지 하나는 문서 하나의 것이라 남겨 둘 이유가 없다. 이미지는
     * 문서 내용이 아니므로 revision 과 이벤트가 없다 — 관계도와 AI 가 읽는 것은 바뀌지 않는다.
     */
    @Transactional
    public FileResponses.Document setImage(UUID ownerUserId, UUID fileId, FileRequests.SetImage request) {
        FileRows.Document document = requireDocument(ownerUserId, fileId);
        requireEditable(document);
        if (request == null || request.imageId() == null) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
        String status = repository.imageStatus(document.projectId(), request.imageId())
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.IMAGE_NOT_FOUND));
        if (!"COMMITTED".equals(status)) {
            throw new ContentFailure(ContentFailure.Reason.OBJECT_NOT_UPLOADED);
        }

        Optional<UUID> previous;
        try {
            previous = repository.replaceImage(fileId, request.imageId());
        } catch (DuplicateKeyException usedElsewhere) {
            // 다른 문서가 이미 쓰는 이미지다. 그 문서의 이미지를 바꾸면 이쪽이 깨진다.
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
        previous.flatMap(repository::deleteImage).ifPresent(key -> mediaCleanup.deleteAfterCommit(List.of(key)));
        activity.touch(document.projectId());
        return document(repository.findDocument(ownerUserId, fileId).orElseThrow());
    }

    /** 이미지를 뗀다. 없었으면 아무것도 하지 않는다. */
    @Transactional
    public void clearImage(UUID ownerUserId, UUID fileId) {
        FileRows.Document document = requireDocument(ownerUserId, fileId);
        requireEditable(document);
        Optional<UUID> previous = repository.replaceImage(fileId, null);
        if (previous.isEmpty()) {
            return;
        }
        repository.deleteImage(previous.get()).ifPresent(key -> mediaCleanup.deleteAfterCommit(List.of(key)));
        activity.touch(document.projectId());
    }

    /** 에피소드만 사라지고 회차는 원고 폴더로 돌아간다(와이어프레임 166). 돌아간 문서마다 위치가 바뀐다. */
    @Transactional
    public void deleteEpisode(UUID ownerUserId, UUID episodeId) {
        requireEpisode(ownerUserId, episodeId);
        UUID projectId = repository.projectOfEpisodeOrNull(episodeId);
        List<UUID> moved = repository.deleteEpisode(episodeId);
        activity.touch(projectId);
        outbox.append(moved.stream()
                .map(id -> messages.fileChanged(projectId, id, ChangeType.UPDATED, Set.of(Field.FOLDER)))
                .toArray(OutboxRecord[]::new));
    }

    private FileResponses.Document document(FileRows.Document row) {
        return FileResponses.Document.from(row, storage::publicUrl);
    }

    private void requireProject(UUID ownerUserId, UUID projectId) {
        if (!repository.projectExists(ownerUserId, projectId)) {
            throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
        }
    }

    private FileRows.Document requireDocument(UUID ownerUserId, UUID fileId) {
        return repository.findDocument(ownerUserId, fileId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.FILE_NOT_FOUND));
    }

    private void requireEpisode(UUID ownerUserId, UUID episodeId) {
        repository.findEpisode(ownerUserId, episodeId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.FILE_NOT_FOUND));
    }

    /** 잠긴 문서는 읽기만 허용한다(요구사항 §3.5). 이름 변경과 이동도 편집이다. */
    private static void requireEditable(FileRows.Document document) {
        if (document.locked()) {
            throw new ContentFailure(ContentFailure.Reason.DOCUMENT_LOCKED);
        }
    }

    private short folderIdOf(String folderCode) {
        return repository.baseFolders().stream()
                .filter(folder -> folder.code().equals(folderCode))
                .map(FileRows.BaseFolder::id)
                .findFirst()
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.INVALID_FILE_LOCATION));
    }

    /**
     * 에피소드는 원고 아래에만 있고, 다른 프로젝트의 에피소드를 가리킬 수 없다. DB에도 같은 규칙이
     * CHECK와 복합 외래 키로 있지만, 그쪽에 걸리면 클라이언트가 받는 것은 제약 이름뿐이다.
     */
    private UUID validateEpisode(UUID projectId, String folderCode, UUID episodeId) {
        if (episodeId == null) {
            return null;
        }
        if (!MANUSCRIPT.equals(folderCode)) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_FILE_LOCATION);
        }
        if (!projectId.equals(repository.projectOfEpisodeOrNull(episodeId))) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_FILE_LOCATION);
        }
        return episodeId;
    }

    private static String validateTitle(String raw) {
        if (raw == null) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_FILE_TITLE);
        }
        String title = raw.trim();
        if (title.isEmpty() || title.length() > TITLE_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_FILE_TITLE);
        }
        return title;
    }

    private static <T> T duplicateAware(Supplier<T> write) {
        try {
            return write.get();
        } catch (DuplicateKeyException exception) {
            throw new ContentFailure(ContentFailure.Reason.FILE_TITLE_TAKEN);
        }
    }

    private static FileRows.Episode duplicateAwareEpisode(Supplier<FileRows.Episode> write) {
        try {
            return write.get();
        } catch (DuplicateKeyException exception) {
            throw new ContentFailure(ContentFailure.Reason.FILE_TITLE_TAKEN);
        }
    }
}
