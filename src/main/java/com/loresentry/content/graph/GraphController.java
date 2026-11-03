package com.loresentry.content.graph;

import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.web.CurrentUser;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GraphController {

    private final GraphService graph;

    GraphController(GraphService graph) {
        this.graph = graph;
    }

    @GetMapping("/projects/{projectId}/graph")
    public GraphResponses.Graph of(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("graph get requested", LogField.projectId(projectId));
        return graph.of(userId, projectId);
    }
}
