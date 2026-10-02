package com.loresentry.content.feedback;

import java.util.UUID;

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
        return ResponseEntity.status(HttpStatus.CREATED).body(feedback.create(userId, request));
    }
}
