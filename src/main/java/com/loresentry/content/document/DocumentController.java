package com.loresentry.content.document;

import java.util.List;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.web.ContentFailure;
import com.loresentry.content.web.CurrentUser;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocumentController {

    /** 저장 재시도를 알아보는 멱등 키. 같은 값이면 revision 을 또 올리지 않는다. */
    public static final String SAVE_ID_HEADER = "X-Save-Id";

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    public record LockRequest(boolean locked) {
    }

    @GetMapping("/files/{fileId}/content")
    public DocumentResponses.Content get(@CurrentUser UUID userId, @PathVariable UUID fileId) {
        AppLogger.current().info("document get requested", LogField.fileId(fileId));
        return documents.get(userId, fileId);
    }

    /**
     * 한 프로젝트의 문서 여러 개를 분석용 평문으로 읽는다({@code ?ids=…&ids=…}). graph-rag 처럼 그래프 탐색으로
     * 문서를 먼저 고른 서비스가 쓴다. gateway 는 이 경로를 중계하지 않는다.
     */
    @GetMapping("/projects/{projectId}/files/content")
    public DocumentResponses.Contents contents(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestParam("ids") List<String> ids) {
        AppLogger.current().info("document contents get requested", LogField.projectId(projectId), LogField.param("ids", ids));
        return documents.contents(userId, projectId, ids);
    }

    /**
     * {@code If-Match}는 필수다. 없으면 클라이언트가 어느 버전을 고쳤다고 주장하는지 알 수 없고,
     * 조건 없는 저장은 남의 변경을 조용히 덮어쓴다.
     */
    @PutMapping("/files/{fileId}/content")
    public DocumentResponses.Content save(
            @CurrentUser UUID userId,
            @PathVariable UUID fileId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = SAVE_ID_HEADER, required = false) String saveId,
            @RequestBody DocumentSnapshot body) {
        // If-Match 는 아직 해석 전 문자열이라 가려져 길이만 남는다. 제목은 길이, 본문 JSON 은 바이트 수, 속성·관계는 개수만 남는다
        AppLogger.current().info("document save requested", LogField.fileId(fileId), LogField.saveId(saveId),
                LogField.param("if_match", ifMatch), LogField.param("title", body == null ? null : body.title()),
                LogField.param("body", body == null ? null : body.body()),
                LogField.param("properties", body == null ? null : body.properties()),
                LogField.param("relations", body == null ? null : body.relations()));
        return documents.save(userId, fileId, revisionOf(ifMatch), saveIdOf(saveId), body);
    }

    @PutMapping("/files/{fileId}/lock")
    public DocumentResponses.Content setLocked(
            @CurrentUser UUID userId,
            @PathVariable UUID fileId,
            @RequestBody LockRequest request) {
        AppLogger.current().info("document lock set requested", LogField.fileId(fileId),
                LogField.param("locked", request == null ? null : request.locked()));
        return documents.setLocked(userId, fileId, request.locked());
    }

    /** ETag 는 따옴표로 감싸 오는 것이 보통이다. 양쪽 모두 받아들인다. */
    static long revisionOf(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();
        try {
            long revision = Long.parseLong(value);
            if (revision < 0) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
            }
            return revision;
        } catch (NumberFormatException invalid) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
    }

    static UUID saveIdOf(String saveId) {
        if (saveId == null || saveId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(saveId.trim());
        } catch (IllegalArgumentException invalid) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
    }

    /** 이름 붙인 버전 요청. */
    public record NamedVersionRequest(@JsonProperty("label") String label) {
    }
}
