package com.loresentry.content.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(ApiErrorResponse.of(exception));
    }

    /**
     * 경로 변수가 UUID로 변환되지 않는 경우다. 그런 id는 존재할 수 없으므로 400이 아니라 404다.
     * 형식이 틀렸다는 사실 자체가 클라이언트에게 줄 정보가 없다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleUnparsablePathVariable(
            MethodArgumentTypeMismatchException exception) {
        return handleApiException(ApiException.notFound(exception.getName() + " is not a known identifier"));
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, HandlerMethodValidationException.class })
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(Exception exception) {
        return handleApiException(ApiException.validation("request body could not be read", null));
    }

    /**
     * 마지막 그물. 원인 메시지를 본문에 싣지 않는다 — 내부 구조가 새어 나간다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("internal", "unexpected server error", null));
    }
}
