package com.loresentry.content.project;

import java.util.List;

/**
 * 배열을 최상위로 돌려주지 않는다. 나중에 {@code nextCursor} 같은 필드를 더할 자리가 필요하다.
 */
public record ProjectListResponse(List<ProjectResponse> projects) {

    static ProjectListResponse of(List<Project> projects) {
        return new ProjectListResponse(projects.stream().map(ProjectResponse::from).toList());
    }
}
