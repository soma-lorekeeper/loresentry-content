package com.loresentry.content.feedback;

import java.util.List;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.web.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WithdrawalSurveyController {

    public record Request(List<String> reasons, String comment) {
    }

    private final WithdrawalSurveyService surveys;

    public WithdrawalSurveyController(WithdrawalSurveyService surveys) {
        this.surveys = surveys;
    }

    @PostMapping("/withdrawal-surveys")
    public ResponseEntity<Void> record(@CurrentUser UUID userId, @RequestBody Request request) {
        // 탈퇴 사유는 고른 항목 수만, 자유 의견은 길이만 남는다
        AppLogger.current().info("withdrawal survey record requested", LogField.param("reasons", request == null ? null : request.reasons()),
                LogField.param("comment", request == null ? null : request.comment()));
        surveys.record(request.reasons(), request.comment());
        return ResponseEntity.noContent().build();
    }
}
