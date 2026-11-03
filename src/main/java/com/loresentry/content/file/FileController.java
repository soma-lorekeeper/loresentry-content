package com.loresentry.content.file;

import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.web.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @GetMapping("/projects/{projectId}/files")
    public FileResponses.Tree tree(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("file tree get requested", LogField.projectId(projectId));
        return files.tree(userId, projectId);
    }

    /** {@code /{projectId}/files}보다 먼저 선언한다. 리터럴이 우선이지만 읽는 순서도 그래야 한다. */
    @GetMapping("/projects/{projectId}/files/trash")
    public FileResponses.TrashList trash(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("file trash list requested", LogField.projectId(projectId));
        return files.trash(userId, projectId);
    }

    @PostMapping("/projects/{projectId}/files")
    public ResponseEntity<Object> create(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestBody FileRequests.Create request) {
        // 새 파일이 들어갈 회차는 새 파일의 범위라 전용 필드(episode_id)다. 종류·분류는 허용 목록 밖이면 other 로 남는다
        AppLogger.current().info("file create requested", LogField.projectId(projectId),
                LogField.episodeId(request == null ? null : request.episodeId()),
                LogField.code("kind", request == null ? null : request.kind(), FileService.KINDS),
                LogField.param("title", request == null ? null : request.title()),
                LogField.code("folder_code", request == null ? null : request.folderCode(), FileService.FOLDER_CODES));
        return ResponseEntity.status(201).body(files.create(userId, projectId, request));
    }

    @PatchMapping("/files/{fileId}")
    public FileResponses.Document rename(
            @CurrentUser UUID userId,
            @PathVariable UUID fileId,
            @RequestBody FileRequests.Rename request) {
        AppLogger.current().info("file rename requested", LogField.fileId(fileId),
                LogField.param("title", request == null ? null : request.title()));
        return files.rename(userId, fileId, request);
    }

    @PatchMapping("/files/{fileId}/position")
    public FileResponses.Document move(
            @CurrentUser UUID userId,
            @PathVariable UUID fileId,
            @RequestBody FileRequests.Move request) {
        // 옮길 위치(분류·회차)와 기준 파일은 대상이 아니라 참조라 역할 이름을 붙인 param 이다
        AppLogger.current().info("file move requested", LogField.fileId(fileId),
                LogField.code("target_folder_code", request == null ? null : request.folderCode(), FileService.FOLDER_CODES),
                LogField.param("target_episode_id", request == null ? null : request.episodeId()),
                LogField.param("before_file_id", request == null ? null : request.beforeFileId()));
        return files.move(userId, fileId, request);
    }

    @PostMapping("/files/{fileId}/trash")
    public ResponseEntity<Void> moveToTrash(@CurrentUser UUID userId, @PathVariable UUID fileId) {
        AppLogger.current().info("file trash requested", LogField.fileId(fileId));
        files.moveToTrash(userId, fileId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/files/{fileId}/restore")
    public FileResponses.Document restore(@CurrentUser UUID userId, @PathVariable UUID fileId) {
        AppLogger.current().info("file restore requested", LogField.fileId(fileId));
        return files.restore(userId, fileId);
    }

    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<Void> deletePermanently(@CurrentUser UUID userId, @PathVariable UUID fileId) {
        AppLogger.current().info("file delete requested", LogField.fileId(fileId));
        files.deletePermanently(userId, fileId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/files/{fileId}/image")
    public FileResponses.Document setImage(
            @CurrentUser UUID userId,
            @PathVariable UUID fileId,
            @RequestBody FileRequests.SetImage request) {
        // 대상은 파일이고 이미지는 연결할 상대라 param 이다
        AppLogger.current().info("file image set requested", LogField.fileId(fileId),
                LogField.param("attached_image_id", request == null ? null : request.imageId()));
        return files.setImage(userId, fileId, request);
    }

    @DeleteMapping("/files/{fileId}/image")
    public ResponseEntity<Void> clearImage(@CurrentUser UUID userId, @PathVariable UUID fileId) {
        AppLogger.current().info("file image clear requested", LogField.fileId(fileId));
        files.clearImage(userId, fileId);
        return ResponseEntity.noContent().build();
    }
}
