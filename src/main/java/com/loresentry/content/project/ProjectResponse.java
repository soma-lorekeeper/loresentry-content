package com.loresentry.content.project;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 프론트엔드 {@code Project} 모델에 대응한다.
 *
 * <p>{@code lastWorkedAt}은 지금 {@code updated_at} 값이다. 문서 저장이 구현되면 같은 트랜잭션에서
 * {@code projects.updated_at}을 touch하므로, 별도 컬럼이 생기더라도 이 필드 이름은 바뀌지 않는다.
 *
 * <p>{@code lastFile}은 {@code document} 테이블이 이번 범위 밖이라 항상 null이다. 프론트 모델이 이미
 * nullable이므로 화면은 이 값을 견딘다.
 */
public record ProjectResponse(
        UUID id,
        String name,
        String description,
        OffsetDateTime lastWorkedAt,
        OffsetDateTime trashedAt,
        OffsetDateTime createdAt,
        Object lastFile) {

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.id(),
                project.name(),
                project.description(),
                project.updatedAt(),
                project.trashedAt(),
                project.createdAt(),
                null);
    }
}
