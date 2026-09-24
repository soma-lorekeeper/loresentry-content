package com.loresentry.content.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

/**
 * {@code { "code", "message", "next_action" }}. authentication 서비스와 같은 계약이다.
 *
 * <p>{@code code}가 계약이고 {@code message}는 진단용 영어다. 프론트엔드는 코드로 문구를 고르므로
 * 이 문장을 화면에 그대로 띄우지 않는다.
 */
public final class ErrorResponses {

    public record Contract(int status, String message, String nextAction) {
    }

    private ErrorResponses() {
    }

    public static Contract contract(ContentFailure.Reason reason) {
        return switch (reason) {
            case INVALID_REQUEST -> new Contract(400, "Invalid request.", "NONE");
            case NOT_FOUND -> new Contract(404, "No such endpoint.", "NONE");
            case INVALID_PROJECT_NAME -> new Contract(400, "Invalid project name.", "NONE");
            case INVALID_PROJECT_DESCRIPTION -> new Contract(400, "Invalid project description.", "NONE");
            case USER_CONTEXT_REQUIRED -> new Contract(401, "User context is required.", "RELOGIN");
            case PROJECT_NOT_FOUND -> new Contract(404, "Project was not found.", "NONE");
            case PROJECT_NAME_TAKEN -> new Contract(409, "Project name is already in use.", "NONE");
            case PROJECT_NOT_TRASHED -> new Contract(409, "Project must be in the trash first.", "NONE");
            case FILE_NOT_FOUND -> new Contract(404, "File was not found.", "NONE");
            case INVALID_FILE_TITLE -> new Contract(400, "Invalid file title.", "NONE");
            case FILE_TITLE_TAKEN -> new Contract(409, "A file with this title is already here.", "NONE");
            case FILE_NOT_TRASHED -> new Contract(409, "File must be in the trash first.", "NONE");
            case INVALID_FILE_LOCATION -> new Contract(400, "That location cannot hold this file.", "NONE");
            case DOCUMENT_LOCKED -> new Contract(409, "Document is locked for editing.", "NONE");
            case INVALID_RELATION_TARGET -> new Contract(400, "Relation target is not usable.", "NONE");
            case VERSION_NOT_FOUND -> new Contract(404, "Version was not found.", "NONE");
            case INTERNAL_ERROR -> new Contract(500, "An internal error occurred.", "NONE");
        };
    }

    public static ResponseEntity<Map<String, Object>> response(Throwable failure) {
        ContentFailure content = failure instanceof ContentFailure value ? value : null;
        var reason = content == null ? ContentFailure.Reason.INTERNAL_ERROR : content.reason();
        var contract = contract(reason);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", reason.name());
        body.put("message", contract.message());
        body.put("next_action", contract.nextAction());

        if (reason == ContentFailure.Reason.INTERNAL_ERROR) {
            logSafe(failure);
        }
        return ResponseEntity.status(contract.status()).body(body);
    }

    /** 예외 메시지·요청 값·SQL은 의도적으로 기록하지 않는다. 내부 구조와 사용자 데이터가 새어 나간다. */
    private static void logSafe(Throwable failure) {
        StringBuilder diagnostic = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            diagnostic.append(cause.getClass().getName()).append('\n');
            java.util.Arrays.stream(cause.getStackTrace()).limit(30)
                    .forEach(frame -> diagnostic.append("  at ").append(frame).append('\n'));
            if (cause.getCause() == cause) {
                break;
            }
        }
        LoggerFactory.getLogger(ErrorResponses.class).error("Unhandled content error:\n{}", diagnostic);
    }
}
