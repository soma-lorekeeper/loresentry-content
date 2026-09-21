package com.loresentry.content.web;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트에게 돌려줄 오류. {@code error} 코드가 계약이고 {@code message}는 진단용이다.
 * 프론트엔드는 코드로 사용자 문구를 고르므로 여기의 문장을 화면에 그대로 띄우지 않는다.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    private final String error;

    private final String field;

    private ApiException(HttpStatus status, String error, String message, String field) {
        super(message);
        this.status = status;
        this.error = error;
        this.field = field;
    }

    public static ApiException validation(String message, String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation", message, field);
    }

    public static ApiException unauthenticated(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", message, null);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", message, null);
    }

    public static ApiException duplicate(String message) {
        return new ApiException(HttpStatus.CONFLICT, "duplicate", message, null);
    }

    public static ApiException invalidState(String message) {
        return new ApiException(HttpStatus.CONFLICT, "invalid_state", message, null);
    }

    public HttpStatus status() {
        return status;
    }

    public String error() {
        return error;
    }

    public String field() {
        return field;
    }
}
