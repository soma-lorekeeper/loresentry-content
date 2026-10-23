package com.loresentry.content.refresh.review;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefreshReviewController {

    private final RefreshReviewService review;

    public RefreshReviewController(RefreshReviewService review) {
        this.review = review;
    }

    /** 가장 최근 최신화의 상태. 화면은 결과를 기다리는 동안 이것을 몇 초마다 다시 읽는다(폴링). */
    @GetMapping("/projects/{projectId}/graph-refresh")
    public RefreshResponses.Summary latest(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return review.latest(userId, projectId);
    }

    /** 최신화 하나와 검토 작업본 전부. */
    @GetMapping("/projects/{projectId}/graph-refresh/{requestId}")
    public RefreshResponses.Detail detail(@CurrentUser UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID requestId) {
        return review.detail(userId, projectId, requestId);
    }

    /** 작업본 한 개의 자동 저장. {@code draft_revision} 이 마지막으로 읽은 값과 다르면 {@code 409} 다. */
    @PatchMapping("/projects/{projectId}/graph-refresh/{requestId}/drafts/{draftId}")
    public RefreshResponses.SavedDraft saveDraft(@CurrentUser UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID requestId, @PathVariable UUID draftId,
            @RequestBody RefreshResponses.DraftSave request) {
        return review.saveDraft(userId, projectId, requestId, draftId, request);
    }
}
