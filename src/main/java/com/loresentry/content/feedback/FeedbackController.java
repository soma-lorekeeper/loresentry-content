package com.loresentry.content.feedback;

import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.web.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedbackController {

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping("/feedback")
    public ResponseEntity<FeedbackDtos.Created> create(
            @CurrentUser UUID userId,
            @RequestBody FeedbackDtos.CreateRequest request) {
        // 의견 본문은 길이만 남는다. 분류는 허용 목록을 가진 service 줄에 남긴다
        AppLogger.current().info("feedback create requested", LogField.param("message", request == null ? null : request.message()));
        return ResponseEntity.status(HttpStatus.CREATED).body(feedback.create(userId, request));
    }
}
