package com.loresentry.content.project;

import java.net.URI;
import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @GetMapping
    public ProjectListResponse list(@CurrentUser UUID userId) {
        return ProjectListResponse.of(projects.listActive(userId));
    }

    /** {@code /{projectId}}보다 먼저 선언한다. 리터럴 세그먼트가 우선이지만 읽는 순서도 그래야 한다. */
    @GetMapping("/trash")
    public ProjectListResponse listTrash(@CurrentUser UUID userId) {
        return ProjectListResponse.of(projects.listTrashed(userId));
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @CurrentUser UUID userId,
            @RequestBody CreateProjectRequest request) {
        Project project = projects.create(userId, request);
        return ResponseEntity.created(URI.create("/projects/" + project.id()))
                .body(ProjectResponse.from(project));
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return ProjectResponse.from(projects.getActive(userId, projectId));
    }

    @PatchMapping("/{projectId}")
    public ProjectResponse update(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestBody UpdateProjectRequest request) {
        return ProjectResponse.from(projects.update(userId, projectId, request));
    }

    @PostMapping("/{projectId}/trash")
    public ResponseEntity<Void> moveToTrash(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        projects.moveToTrash(userId, projectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{projectId}/restore")
    public ProjectResponse restore(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return ProjectResponse.from(projects.restore(userId, projectId));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deletePermanently(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId) {
        projects.deletePermanently(userId, projectId);
        return ResponseEntity.noContent().build();
    }
}
