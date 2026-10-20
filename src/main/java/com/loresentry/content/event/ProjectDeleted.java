package com.loresentry.content.event;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code content.project.changed.v1} 의 ProjectDeleted value. 프로젝트 tombstone 이다 — 소비자는 그 프로젝트의
 * 문서를 모두 지운다. 문서별 DELETED 는 따로 나가지 않는다.
 *
 * @param reason {@code PROJECT_DELETED} 또는 {@code OWNER_DELETED}. 기록용이며 처리를 다르게 하지 않는다
 */
public record ProjectDeleted(@JsonProperty("project_id") UUID projectId, String reason) {
}
