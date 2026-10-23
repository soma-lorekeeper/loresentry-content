package com.loresentry.content.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

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
            JsonNode body,
            @JsonProperty("legacy_body_md") String legacyBodyMd,
            List<DocumentSnapshot.TextProperty> properties,
            List<DocumentSnapshot.Relation> relations,
            boolean locked,
            @JsonProperty("char_count") int charCount,
            @JsonProperty("revision_no") long revisionNo,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {

        public DocumentSnapshot snapshot() {
            return new DocumentSnapshot(title, body, legacyBodyMd, properties, relations);
        }
    }

    /**
     * 여러 문서의 내용을 한 번에 읽은 결과. 다른 서비스가 그래프 탐색 등으로 고른 문서를 읽을 때 쓴다.
     *
     * @param missing 요청했지만 열 수 없는 문서 — 휴지통에 있거나, 없거나, 다른 프로젝트의 것. 단건 조회가
     *                {@code FILE_NOT_FOUND} 로 답하는 경우와 같다
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Contents(
            @JsonProperty("project_id") UUID projectId,
            List<ContentItem> documents,
            List<UUID> missing) {
    }

    /**
     * 분석용 문서 하나. 편집 화면이 쓰는 서식 JSON·잠금·글자 수·수정 시각은 없고 평문({@code body_text})만 있다.
     *
     * @param bodyText 서버가 본문에서 뽑은 평문. 저장한 적이 없는 문서는 빈 문자열이다
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ContentItem(
            UUID id,
            @JsonProperty("revision_no") long revisionNo,
            @JsonProperty("folder_code") String folderCode,
            String title,
            @JsonProperty("body_text") String bodyText,
            List<DocumentSnapshot.TextProperty> properties,
            List<DocumentSnapshot.Relation> relations) {
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
