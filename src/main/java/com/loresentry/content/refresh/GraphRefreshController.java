package com.loresentry.content.refresh;

import com.loresentry.content.web.CurrentUser;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/projects/{projectId}/graph-refresh")
@ConditionalOnProperty(name = "graph-refresh.enabled", havingValue = "true")
public class GraphRefreshController {
    private final GraphRefreshRequests requests;

    public GraphRefreshController(GraphRefreshRequests requests) {
        this.requests = requests;
    }

    public record Request(List<UUID> files) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> request(
            @CurrentUser UUID user, @PathVariable UUID projectId, @RequestBody Request body)
            throws Exception {
        return ResponseEntity.accepted().body(requests.request(user, projectId, body.files()));
    }

    @GetMapping("/{requestId}")
    public Map<String, Object> get(
            @CurrentUser UUID user, @PathVariable UUID projectId, @PathVariable UUID requestId) {
        return requests.get(user, projectId, requestId);
    }
}
