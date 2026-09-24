package com.loresentry.content.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class DocumentResponses {

    private DocumentResponses() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Content(
            UUID id,
            @JsonProperty("project_id") UUID projectId,
            String title,
            @JsonProperty("folder_code") String folderCode,
            @JsonProperty("episode_id") UUID episodeId,
            @JsonProperty("body_md") String bodyMd,
            List<DocumentSnapshot.TextProperty> properties,
            List<DocumentSnapshot.Relation> relations,
            boolean locked,
            @JsonProperty("char_count") int charCount,
            @JsonProperty("revision_no") long revisionNo,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {

        public DocumentSnapshot snapshot() {
            return new DocumentSnapshot(title, bodyMd, properties, relations);
        }
    }

    /** 버전 목록. 스냅샷을 함께 싣는다 — 비교 화면이 목록을 받은 뒤 다시 요청하지 않도록. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Version(
            UUID id,
            @JsonProperty("file_id") UUID fileId,
            String kind,
            String label,
            @JsonProperty("source_revision_no") long sourceRevisionNo,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            DocumentSnapshot snapshot) {
    }

    public record VersionList(List<Version> versions) {
    }
}
