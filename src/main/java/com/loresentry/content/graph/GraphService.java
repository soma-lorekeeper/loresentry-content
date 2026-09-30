package com.loresentry.content.graph;

import java.util.UUID;

import com.loresentry.content.web.ContentFailure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class GraphService {

    private final GraphRepository repository;

    GraphService(GraphRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    GraphResponses.Graph of(UUID ownerUserId, UUID projectId) {
        if (!repository.projectExists(ownerUserId, projectId)) {
            throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
        }
        return new GraphResponses.Graph(
                repository.nodes(projectId),
                repository.edges(projectId),
                repository.episodes(projectId));
    }
}
