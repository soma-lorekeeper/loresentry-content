package com.loresentry.content.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.document.DocumentConflict;

import org.springframework.beans.TypeMismatchException;
import org.springframework.core.NestedExceptionUtils;
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

    /** 컨트롤러가 쓰는 경로 변수 이름. 어느 변수가 UUID 가 아니었는지만 남기고 값은 남기지 않는다. */
    private static final Set<String> PATH_VARIABLES = Set.of("projectId", "fileId", "versionId", "episodeId", "imageId",
            "memoId", "requestId", "draftId");

    /**
     * 경로 변수가 UUID로 변환되지 않는 경우다. 그런 id는 존재할 수 없으므로 잘못된 요청이 아니라
     * 없는 프로젝트로 답한다 — 형식이 틀렸다는 사실은 클라이언트에게 줄 정보가 없다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleUnparsablePathVariable(
            MethodArgumentTypeMismatchException exception) {
        // 404 의 이유가 "그런 id 가 없음"이 아니라 형식 오류임을 결과 줄에서 가른다. 목록 밖 이름(쿼리 파라미터 등)은 other
        AppLogger.current().addToOutcome(LogField.code("unparsable_path_variable", exception.getName(), PATH_VARIABLES));
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
        // 문의 받은 값을 Kibana 에서 바로 찾도록 trace ID 를 싣는다(LOG_SCHEMA.md 7절 응답)
        String traceId = AppLogger.current().traceId();
        if (traceId != null) {
            body.put("trace_id", traceId);
        }
        body.put("current", conflict.current());
        body.put("base", conflict.base());
        // 충돌은 결과 줄(request completed, warn)에 이유로 붙인다
        AppLogger.current().addToOutcome(LogField.errorType("DOCUMENT_CONFLICT"));
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
            // 결과 줄의 이유는 INVALID_REQUEST 하나라, 어느 단계(본문 읽기·헤더·검증 등)에서 거절됐는지 가장 안쪽 원인의
            // 종류로 남긴다. 메시지에는 받은 값이 들어 있어 남기지 않는다
            AppLogger.current().warn("request rejected by framework",
                    LogField.errorType(NestedExceptionUtils.getMostSpecificCause(failure).getClass().getName()));
            return ErrorResponses.response(new ContentFailure(ContentFailure.Reason.INVALID_REQUEST));
        }
        return ErrorResponses.response(failure);
    }
}
