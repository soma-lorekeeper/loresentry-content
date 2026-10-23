package com.loresentry.content.refresh.confirm;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefreshConfirmController {

    private final RefreshConfirmService confirm;

    public RefreshConfirmController(RefreshConfirmService confirm) {
        this.confirm = confirm;
    }

    /**
     * 검토를 마친 최신화를 확정한다. 모든 작업본이 맞춰져 있어야 한다. 반영하고 나면 run 은 APPLIED 이고 문서 잠금이
     * 풀린다. 본문이 없다.
     */
    @PostMapping("/projects/{projectId}/graph-refresh/{requestId}/confirm")
    public ConfirmResponses.Confirmed confirm(@CurrentUser UUID userId, @PathVariable UUID projectId,
            @PathVariable UUID requestId) {
        return confirm.confirm(userId, projectId, requestId);
    }
}
