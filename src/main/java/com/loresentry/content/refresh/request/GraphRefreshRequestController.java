package com.loresentry.content.refresh.request;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GraphRefreshRequestController {

    private final GraphRefreshRequestService refresh;

    public GraphRefreshRequestController(GraphRefreshRequestService refresh) {
        this.refresh = refresh;
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
}
