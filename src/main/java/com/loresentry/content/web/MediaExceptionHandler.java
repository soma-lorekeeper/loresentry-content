package com.loresentry.content.web;

import java.util.Map;

import com.loresentry.content.media.ImageNotFoundException;
import com.loresentry.content.media.InvalidUploadRequestException;
import com.loresentry.content.media.ObjectNotUploadedException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class MediaExceptionHandler {

    @ExceptionHandler(InvalidUploadRequestException.class)
    public ResponseEntity<Map<String, String>> invalidUpload(InvalidUploadRequestException exception) {
        return body(HttpStatus.BAD_REQUEST, "invalid_upload_request", exception.getMessage());
    }

    @ExceptionHandler(ImageNotFoundException.class)
    public ResponseEntity<Map<String, String>> imageNotFound(ImageNotFoundException exception) {
        return body(HttpStatus.NOT_FOUND, "image_not_found", exception.getMessage());
    }

    @ExceptionHandler(ObjectNotUploadedException.class)
    public ResponseEntity<Map<String, String>> objectNotUploaded(ObjectNotUploadedException exception) {
        return body(HttpStatus.CONFLICT, "object_not_uploaded", exception.getMessage());
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of("error", error, "message", message));
    }
}
