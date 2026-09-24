package com.loresentry.content.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.loresentry.content.document.DocumentConflict;

import org.springframework.beans.TypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ContentExceptionHandler {

    /**
     * 경로 변수가 UUID로 변환되지 않는 경우다. 그런 id는 존재할 수 없으므로 잘못된 요청이 아니라
     * 없는 프로젝트로 답한다 — 형식이 틀렸다는 사실은 클라이언트에게 줄 정보가 없다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleUnparsablePathVariable(
            MethodArgumentTypeMismatchException exception) {
        return ErrorResponses.response(new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND));
    }

    /**
     * 핸들러가 없는 경로다. 아래 {@code Exception} 그물이 이것까지 삼키면 오타 난 경로가 500이 되고,
     * 404마다 스택 트레이스가 남는다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownPath(NoResourceFoundException exception) {
        return ErrorResponses.response(new ContentFailure(ContentFailure.Reason.NOT_FOUND));
    }

    /**
     * 충돌 응답에만 {@code current}와 {@code base}가 더 붙는다. 클라이언트가 3-way 병합을 하려면
     * 현재 문서와 공통 조상이 같은 응답에 있어야 한다 — 다시 GET 하면 그 사이에 또 바뀔 수 있다.
     */
    @ExceptionHandler(DocumentConflict.class)
    public ResponseEntity<Map<String, Object>> handleConflict(DocumentConflict conflict) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "DOCUMENT_CONFLICT");
        body.put("message", "Document was saved elsewhere first.");
        body.put("next_action", "NONE");
        body.put("current", conflict.current());
        body.put("base", conflict.base());
        return ResponseEntity.status(409).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception failure) {
        if (failure instanceof HttpMessageNotReadableException
                || failure instanceof ServletRequestBindingException
                || failure instanceof MethodArgumentNotValidException
                || failure instanceof TypeMismatchException
                || failure instanceof HttpRequestMethodNotSupportedException
                || failure instanceof HttpMediaTypeNotSupportedException
                || failure instanceof HttpMediaTypeNotAcceptableException) {
            return ErrorResponses.response(new ContentFailure(ContentFailure.Reason.INVALID_REQUEST));
        }
        return ErrorResponses.response(failure);
    }
}
