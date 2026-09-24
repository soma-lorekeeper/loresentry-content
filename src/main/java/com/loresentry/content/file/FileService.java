package com.loresentry.content.file;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.loresentry.content.web.ContentFailure;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileService {

    static final int TITLE_MAX = 255;

    /** {@code base_folders} 시드의 원고 폴더. 에피소드가 붙을 수 있는 유일한 분류다. */
    private static final String MANUSCRIPT = "MANUSCRIPT";

    private final FileRepository repository;

    public FileService(FileRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public FileResponses.Tree tree(UUID ownerUserId, UUID projectId) {
        requireProject(ownerUserId, projectId);
        return FileResponses.tree(repository.baseFolders(), repository.episodes(projectId),
                repository.activeDocuments(projectId));
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

        return switch (kind) {
            case "document" -> FileResponses.Document.from(createDocument(projectId, request, title));
            case "episode" -> createEpisode(ownerUserId, projectId, title);
            default -> throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        };
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

        return FileResponses.Document.from(
                duplicateAware(() -> repository.renameDocument(fileId, title)));
    }

    @Transactional
    public FileResponses.Episode renameEpisode(UUID ownerUserId, UUID episodeId, FileRequests.Rename request) {
        requireEpisode(ownerUserId, episodeId);
        String name = validateTitle(request.title());

        return FileResponses.Episode.from(
                duplicateAwareEpisode(() -> repository.renameEpisode(episodeId, name)));
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

        return FileResponses.Document.from(
                duplicateAware(() -> repository.moveDocument(fileId, folderId, episodeId, rank)));
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
        repository.trashDocument(fileId);
    }

    @Transactional
    public FileResponses.Document restore(UUID ownerUserId, UUID fileId) {
        FileRows.Document document = requireDocument(ownerUserId, fileId);
        if (!document.isTrashed()) {
            return FileResponses.Document.from(document);
        }

        // 원래 있던 에피소드가 사라졌으면 원고 폴더 최상위로 돌린다(요구사항 §4.2).
        boolean dropEpisode = document.episodeId() != null
                && !repository.episodeStillExists(document.episodeId());
        UUID episodeId = dropEpisode ? null : document.episodeId();

        List<String> siblings = repository.siblingRanks(
                document.projectId(), document.folderId(), episodeId, fileId);
        String rank = Ranks.between(siblings.isEmpty() ? null : siblings.getLast(), null);

        return FileResponses.Document.from(
                duplicateAware(() -> repository.restoreDocument(fileId, dropEpisode, rank)));
    }

    @Transactional
    public void deletePermanently(UUID ownerUserId, UUID fileId) {
        FileRows.Document document = requireDocument(ownerUserId, fileId);
        if (!document.isTrashed()) {
            throw new ContentFailure(ContentFailure.Reason.FILE_NOT_TRASHED);
        }
        repository.deleteDocument(fileId);
    }

    /** 에피소드만 사라지고 회차는 원고 폴더로 돌아간다(와이어프레임 166). */
    @Transactional
    public void deleteEpisode(UUID ownerUserId, UUID episodeId) {
        requireEpisode(ownerUserId, episodeId);
        repository.deleteEpisode(episodeId);
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

    private static FileRows.Document duplicateAware(Supplier<FileRows.Document> write) {
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
