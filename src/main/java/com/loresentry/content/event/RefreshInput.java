package com.loresentry.content.event;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 그래프 최신화 입력. S3 의 {@code refresh/{project_id}/{request_id}/input.json} 에 이 모양 그대로 올라가고,
 * GraphRefreshRequested 이벤트는 그 위치와 바이트의 sha256 만 싣는다(claim check).
 *
 * <p>중복 없이 문서의 정보만 담는다. 어느 프로젝트·요청의 입력인지는 이벤트가 말하고, 이벤트의 fingerprint 가 이
 * 바이트를 고정한다. 문서 원소는 문서 목록 API({@code GET /projects/{id}/files/content})의 원소와 같은 모양이다.
 *
 * @param schemaVersion 이 모양의 버전. 필드를 바꾸면 올린다
 * @param documents     지난 최신화 이후 바뀌었거나 새로 생긴 활성 문서. 휴지통 문서는 싣지 않는다
 */
public record RefreshInput(
        @JsonProperty("schema_version") String schemaVersion,
        List<Document> documents) {

    public static final String SCHEMA_VERSION = "refresh-input-v2";

    /**
     * 문서 하나. 서식 JSON·잠금·정렬 순서 같은 편집 화면용 값은 싣지 않는다.
     *
     * @param bodyText 분석용 평문. 저장한 적이 없는 문서는 빈 문자열이다
     */
    public record Document(
            UUID id,
            @JsonProperty("revision_no") long revisionNo,
            @JsonProperty("folder_code") String folderCode,
            String title,
            @JsonProperty("body_text") String bodyText,
            List<Property> properties,
            List<Relation> relations) {
    }

    public record Property(String key, String value) {
    }

    /**
     * 이 문서에서 본 관계. 한 쌍이 한 행이라 양쪽 문서 모두에 실리며, 키는 상대 문서의 분류가 정한다
     * (원고에서 캐릭터를 보면 {@code related_character}). 휴지통에 있는 상대로 가는 관계는 싣지 않는다.
     */
    public record Relation(
            @JsonProperty("target_document_id") UUID targetDocumentId,
            @JsonProperty("relation_key") String relationKey,
            String description) {
    }
}
