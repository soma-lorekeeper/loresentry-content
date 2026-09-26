package com.loresentry.content.workspace;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 프로젝트를 다시 열 때 탭·패널·그래프 보기를 복원한다(요구사항 §3).
 *
 * <p>서버는 이 JSON 의 구조를 해석하지 않는다. 화면 레이아웃이 바뀔 때마다 마이그레이션을 하지
 * 않으려면 여기서는 불투명한 값이어야 한다. 대신 상태가 없을 때 404 가 아니라
 * {@code {"layout": null}} 을 준다 — 처음 여는 프로젝트는 오류가 아니다.
 */
@RestController
public class WorkspaceStateController {

    private final WorkspaceStateService state;

    public WorkspaceStateController(WorkspaceStateService state) {
        this.state = state;
    }

    @GetMapping("/projects/{projectId}/workspace-state")
    public WorkspaceStateService.State load(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return state.load(userId, projectId);
    }

    @PutMapping("/projects/{projectId}/workspace-state")
    public ResponseEntity<Void> save(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestBody WorkspaceStateService.State body) {
        state.save(userId, projectId, body);
        return ResponseEntity.noContent().build();
    }
}
