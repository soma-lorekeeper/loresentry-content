package com.loresentry.content.web;

import java.util.Map;

/**
 * 도메인 실패. HTTP 상태나 프레임워크 예외를 담지 않는다 — 그 매핑은 {@link ErrorResponses}가 한다.
 *
 * <p>authentication 서비스의 {@code AuthFailure}와 같은 모양이다. 두 서비스가 같은 gateway를 지나
 * 같은 프론트엔드로 가므로 오류 표현이 갈리면 클라이언트가 서비스별 분기를 갖게 된다.
 */
public class ContentFailure extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        INVALID_PROJECT_NAME,
        INVALID_PROJECT_DESCRIPTION,
        USER_CONTEXT_REQUIRED,
        PROJECT_NOT_FOUND,
        PROJECT_NAME_TAKEN,
        PROJECT_NOT_TRASHED,
        FILE_NOT_FOUND,
        INVALID_FILE_TITLE,
        FILE_TITLE_TAKEN,
        FILE_NOT_TRASHED,
        INVALID_FILE_LOCATION,
        DOCUMENT_LOCKED,
        INVALID_RELATION_TARGET,
        VERSION_NOT_FOUND,
        INVALID_UPLOAD_REQUEST,
        IMAGE_NOT_FOUND,
        OBJECT_NOT_UPLOADED,
        MEMO_NOT_FOUND,
        INVALID_MEMO,
        INVALID_FEEDBACK,
        FEEDBACK_RATE_LIMITED,
        INVALID_WITHDRAWAL_SURVEY,
        REFRESH_IN_PROGRESS,
        REFRESH_UNAVAILABLE,
        DOCUMENT_REFRESH_LOCKED,
        REFRESH_REVIEW_PENDING,
        REFRESH_NOT_FOUND,
        REFRESH_DRAFT_NOT_FOUND,
        REFRESH_NOT_READY,
        REFRESH_DRAFT_CONFLICT,
        REFRESH_DRAFTS_UNMATCHED,
        REFRESH_DOCUMENTS_CHANGED,
        REFRESH_DRAFTS_INVALID,
        INTERNAL_ERROR
    }

    private final Reason reason;

    /**
     * 클라이언트가 다음 행동을 고르는 데 필요한 값(예: 맞추지 않은 작업본 id 목록). 응답 본문에 code·message 와
     * 나란히 실린다. 사용자 데이터나 내부 구조를 담지 않는다.
     */
    private final Map<String, Object> details;

    public ContentFailure(Reason reason) {
        this(reason, Map.of());
    }

    public ContentFailure(Reason reason, Map<String, Object> details) {
        super(reason.name());
        this.reason = reason;
        this.details = Map.copyOf(details);
    }

    public Reason reason() {
        return reason;
    }

    public Map<String, Object> details() {
        return details;
    }
}
