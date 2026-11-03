package com.loresentry.content.document;

import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.web.CurrentUser;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VersionController {

    private final DocumentService documents;

    public VersionController(DocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/files/{fileId}/versions")
    public DocumentResponses.VersionList list(@CurrentUser UUID userId, @PathVariable UUID fileId) {
        AppLogger.current().info("document version list requested", LogField.fileId(fileId));
        return documents.versions(userId, fileId);
    }

    @PostMapping("/files/{fileId}/versions")
    public ResponseEntity<DocumentResponses.Version> saveNamed(
            @CurrentUser UUID userId,
            @PathVariable UUID fileId,
            @RequestBody(required = false) DocumentController.NamedVersionRequest request) {
        AppLogger.current().info("document version create requested", LogField.fileId(fileId),
                LogField.param("label", request == null ? null : request.label()));
        DocumentResponses.Version version = documents.saveNamed(userId, fileId,
                request == null ? null : request.label());
        return ResponseEntity.status(201).body(version);
    }

    /**
     * 복원도 저장이므로 {@code If-Match}를 요구한다. 그렇지 않으면 다른 탭이 방금 저장한 내용을
     * 복원이 조용히 덮어쓴다.
     */
    @PostMapping("/files/{fileId}/versions/{versionId}/restore")
    public DocumentResponses.Content restore(
            @CurrentUser UUID userId,
            @PathVariable UUID fileId,
            @PathVariable UUID versionId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        // 파일은 범위, 버전은 대상이다
        AppLogger.current().info("document version restore requested", LogField.fileId(fileId), LogField.versionId(versionId),
                LogField.param("if_match", ifMatch));
        return documents.restore(userId, fileId, versionId, DocumentController.revisionOf(ifMatch));
    }

    @DeleteMapping("/files/{fileId}/versions/{versionId}")
    public ResponseEntity<Void> delete(
            @CurrentUser UUID userId,
            @PathVariable UUID fileId,
            @PathVariable UUID versionId) {
        AppLogger.current().info("document version delete requested", LogField.fileId(fileId), LogField.versionId(versionId));
        documents.deleteVersion(userId, fileId, versionId);
        return ResponseEntity.noContent().build();
    }
}
