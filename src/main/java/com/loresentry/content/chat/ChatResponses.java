package com.loresentry.content.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ChatResponses {

    private ChatResponses() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Project(UUID id, String name, String description) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Manuscript(
            UUID id,
            String title,
            int ordinal,
            @JsonProperty("episode_name") String episodeName,
            List<Property> properties,
            @JsonProperty("revision_no") long revisionNo,
            @JsonProperty("body_text") String bodyText,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {
    }

    public record Property(String key, String value) {
    }

    public record Setting(
            UUID id,
            @JsonProperty("folder_code") String folderCode,
            String title,
            List<Property> properties,
            @JsonProperty("revision_no") long revisionNo,
            @JsonProperty("body_text") String bodyText,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {
    }

    public record Relation(
            @JsonProperty("low_document_id") UUID lowDocumentId,
            @JsonProperty("high_document_id") UUID highDocumentId,
            String description) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Memo(
            UUID id,
            String scope,
            @JsonProperty("document_id") UUID documentId,
            String title,
            String body,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {
    }

    public record Snapshot(
            Project project,
            List<Manuscript> manuscripts,
            List<Setting> settings,
            List<Relation> relations,
            List<Memo> memos,
            long size) {
    }

    public record Size(long size) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MapManuscript(
            UUID id,
            String title,
            int ordinal,
            @JsonProperty("episode_name") String episodeName,
            List<Property> properties,
            @JsonProperty("revision_no") long revisionNo,
            long chars,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {
    }

    public record MapSetting(
            UUID id,
            @JsonProperty("folder_code") String folderCode,
            String title,
            List<Property> properties,
            @JsonProperty("revision_no") long revisionNo,
            long chars,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MapMemo(
            UUID id,
            String scope,
            @JsonProperty("document_id") UUID documentId,
            String title,
            long chars,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {
    }

    public record ChatMap(
            Project project,
            List<MapManuscript> manuscripts,
            List<MapSetting> settings,
            List<Relation> relations,
            List<MapMemo> memos) {
    }

    public record DocumentsRequest(
            @JsonProperty("document_ids") List<UUID> documentIds,
            @JsonProperty("memo_ids") List<UUID> memoIds) {
    }

    public record Documents(
            List<Manuscript> manuscripts,
            List<Setting> settings,
            List<Memo> memos,
            List<UUID> missing) {
    }
}
