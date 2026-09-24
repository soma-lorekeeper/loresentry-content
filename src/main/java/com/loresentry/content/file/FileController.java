package com.loresentry.content.file;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
        return files.tree(userId, projectId);
    }

    /** {@code /{projectId}/files}보다 먼저 선언한다. 리터럴이 우선이지만 읽는 순서도 그래야 한다. */
    @GetMapping("/projects/{projectId}/files/trash")
    public FileResponses.TrashList trash(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return files.trash(userId, projectId);
    }

    @PostMapping("/projects/{projectId}/files")
    public ResponseEntity<Object> create(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestBody FileRequests.Create request) {
        return ResponseEntity.status(201).body(files.create(userId, projectId, request));
    }

    @PatchMapping("/files/{fileId}")
    public FileResponses.Document rename(
            @CurrentUser UUID userId,
            @PathVariable UUID fileId,
            @RequestBody FileRequests.Rename request) {
        return files.rename(userId, fileId, request);
    }

    @PatchMapping("/files/{fileId}/position")
    public FileResponses.Document move(
            @CurrentUser UUID userId,
            @PathVariable UUID fileId,
            @RequestBody FileRequests.Move request) {
        return files.move(userId, fileId, request);
    }

    @PostMapping("/files/{fileId}/trash")
    public ResponseEntity<Void> moveToTrash(@CurrentUser UUID userId, @PathVariable UUID fileId) {
        files.moveToTrash(userId, fileId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/files/{fileId}/restore")
    public FileResponses.Document restore(@CurrentUser UUID userId, @PathVariable UUID fileId) {
        return files.restore(userId, fileId);
    }

    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<Void> deletePermanently(@CurrentUser UUID userId, @PathVariable UUID fileId) {
        files.deletePermanently(userId, fileId);
        return ResponseEntity.noContent().build();
    }
}
