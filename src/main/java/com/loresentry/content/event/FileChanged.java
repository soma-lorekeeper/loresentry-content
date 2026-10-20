package com.loresentry.content.event;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * {@code content.project.changed.v1} 의 FileChanged value. 바뀐 부분이 아니라 발행 시점의 문서 전체다.
 * 필드의 뜻과 동작별 규칙은 {@code docs/events/content-project-changed-v1.md} 3·4절에 있다.
 *
 * @param file      DELETED 면 null
 * @param relations 이 문서가 low 인 관계만(관계 단일 소유). DELETED 면 null
 */
public record FileChanged(
        @JsonProperty("project_id") UUID projectId,
        @JsonProperty("file_id") UUID fileId,
        @JsonProperty("change_type") ChangeType changeType,
        @JsonProperty("changed_fields") List<Field> changedFields,
        File file,
        List<Relation> relations) {

    /** 이 이벤트를 낸 동작. 상태(휴지통 여부)는 이것이 아니라 {@code file.state} 가 말한다. */
    public enum ChangeType {
        CREATED, UPDATED, TRASHED, RESTORED, DELETED
    }

    /** payload 의 어느 부분이 직전 상태와 달라졌나. {@code changed_fields} 에는 이 선언 순서로 나열한다. */
    public enum Field {
        TITLE, BODY, PROPERTIES, FOLDER, STATE, RELATIONS
    }

    public enum State {
        ACTIVE, TRASHED
    }

    /** 문서의 내용·위치·상태. 정렬 순서(rank)·잠금·서식 JSON 은 편집 화면용이라 싣지 않는다. */
    public record File(
            String title,
            @JsonProperty("folder_code") String folderCode,
            @JsonProperty("episode_id") UUID episodeId,
            State state,
            @JsonProperty("body_text") String bodyText,
            List<Property> properties) {
    }

    public record Property(String key, String value) {
    }

    /** owner(low) 문서에서 본 관계. 상대는 언제나 high 문서다. */
    public record Relation(
            @JsonProperty("relation_id") UUID relationId,
            @JsonProperty("target_document_id") UUID targetDocumentId,
            String description) {
    }
}
