package com.loresentry.content.web;

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
        INTERNAL_ERROR
    }

    private final Reason reason;

    public ContentFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
