package com.loresentry.content.project;

import java.net.URI;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
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
        AppLogger.current().info("project list requested");
        return ProjectListResponse.of(projects.listActive(userId));
    }

    /** {@code /{projectId}}보다 먼저 선언한다. 리터럴 세그먼트가 우선이지만 읽는 순서도 그래야 한다. */
    @GetMapping("/trash")
    public ProjectListResponse listTrash(@CurrentUser UUID userId) {
        AppLogger.current().info("project trash list requested");
        return ProjectListResponse.of(projects.listTrashed(userId));
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @CurrentUser UUID userId,
            @RequestBody CreateProjectRequest request) {
        // 이름·설명은 가려져 길이만 남는다
        AppLogger.current().info("project create requested", LogField.param("name", request == null ? null : request.name()),
                LogField.param("description", request == null ? null : request.description()));
        Project project = projects.create(userId, request);
        return ResponseEntity.created(URI.create("/projects/" + project.id()))
                .body(ProjectResponse.from(project));
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("project get requested", LogField.projectId(projectId));
        return ProjectResponse.from(projects.getActive(userId, projectId));
    }

    @PatchMapping("/{projectId}")
    public ProjectResponse update(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestBody UpdateProjectRequest request) {
        // 보내지 않은(null) 칸은 줄에서 빠져, 무엇을 바꾸려 했는지 보인다
        AppLogger.current().info("project update requested", LogField.projectId(projectId),
                LogField.param("name", request == null ? null : request.name()),
                LogField.param("description", request == null ? null : request.description()));
        return ProjectResponse.from(projects.update(userId, projectId, request));
    }

    @PostMapping("/{projectId}/trash")
    public ResponseEntity<Void> moveToTrash(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("project trash requested", LogField.projectId(projectId));
        projects.moveToTrash(userId, projectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{projectId}/restore")
    public ProjectResponse restore(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("project restore requested", LogField.projectId(projectId));
        return ProjectResponse.from(projects.restore(userId, projectId));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deletePermanently(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId) {
        AppLogger.current().info("project delete requested", LogField.projectId(projectId));
        projects.deletePermanently(userId, projectId);
        return ResponseEntity.noContent().build();
    }
}
