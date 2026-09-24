package com.loresentry.content.project;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 프론트엔드 {@code Project} 모델에 대응한다. 필드 이름은 authentication 서비스와 같은 snake case다.
 *
 * <p>{@code last_worked_at}은 지금 {@code updated_at} 값이다. 문서 저장이 구현되면 같은 트랜잭션에서
 * {@code projects.updated_at}을 touch하므로, 별도 컬럼이 생기더라도 이 필드 이름은 바뀌지 않는다.
 *
 * <p>{@code last_file}은 {@code document} 테이블이 이번 범위 밖이라 항상 null이다. 프론트 모델이 이미
 * nullable이므로 화면은 이 값을 견딘다. null도 키로 나가야 하므로 {@code ALWAYS}다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProjectResponse(
        UUID id,
        String name,
        String description,
        @JsonProperty("last_worked_at") OffsetDateTime lastWorkedAt,
        @JsonProperty("trashed_at") OffsetDateTime trashedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("last_file") Object lastFile) {

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
