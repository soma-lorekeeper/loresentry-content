package com.loresentry.content.refresh;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GraphRefreshController {

    private final GraphRefreshService refresh;

    private final RefreshReviewService review;

    private final RefreshConfirmService confirm;

    public GraphRefreshController(GraphRefreshService refresh, RefreshReviewService review,
            RefreshConfirmService confirm) {
        this.refresh = refresh;
        this.review = review;
        this.confirm = confirm;
    }

    /**
     * 검토를 마친 최신화를 확정한다. 모든 작업본이 맞춰져 있어야 한다. 반영하고 나면 run 은 APPLIED 이고 문서 잠금이
     * 풀린다. 본문이 없다.
     */
    @PostMapping("/projects/{projectId}/graph-refresh/{requestId}/confirm")
    public RefreshResponses.Confirmed confirm(@CurrentUser UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID requestId) {
        return confirm.confirm(userId, projectId, requestId);
    }

    /**
     * 본문이 없다. 보낼 문서는 서버가 지난 최신화와 비교해 고른다. 보냈으면 {@code 202}, 바뀐 문서가 없어 아무것도
     * 하지 않았으면 {@code 200} 이다.
     */
    @PostMapping("/projects/{projectId}/graph-refresh")
    public ResponseEntity<GraphRefreshResponse> request(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        GraphRefreshResponse response = refresh.request(userId, projectId);
        HttpStatus status = response.status() == GraphRefreshResponse.Status.GENERATING
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
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
