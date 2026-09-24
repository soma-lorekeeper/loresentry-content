package com.loresentry.content.web;

import java.util.Map;

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
