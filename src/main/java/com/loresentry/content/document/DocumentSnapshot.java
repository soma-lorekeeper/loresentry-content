package com.loresentry.content.document;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/**
 * 문서의 전체 상태. 저장 요청, 응답, 버전 스냅샷이 같은 모양을 쓴다 — 셋이 어긋나면
 * 버전 복원이 "복원했는데 일부가 빠졌다"가 된다.
 *
 * <p>화면이 쓰는 {@code label}·{@code targetType} 같은 표시 정보는 담지 않는다. 그것은 속성 키에서
 * 파생되는 값이고, 서버에 컬럼도 없다. 데이터는 서버가, 표시는 화면이 갖는다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DocumentSnapshot(
        String title,
        /**
         * 본문. {@code {"schema_version":1,"doc":{...}}} 모양이다. 새 데이터는 이것만 채운다.
         */
        JsonNode body,
        /**
         * 변환 전 Markdown 본문. 옛 문서 행과 옛 버전 스냅샷에만 있다. 저장 요청에 오면 무시한다 —
         * Markdown 을 해석하는 곳은 서버가 아니라 프론트다.
         */
        @JsonProperty("legacy_body_md") @JsonAlias("body_md") String legacyBodyMd,
        List<TextProperty> properties,
        List<Relation> relations) {

    public record TextProperty(String key, String value) {
    }

    /**
     * 관계 한 줄. {@code description}은 이 관계가 무엇인지 적는 칸이다(요구사항 §10) — 대상 문서의
     * 설명이 아니라 <b>이 연결</b>의 설명이라, 같은 인물이라도 회차마다 다르게 적힐 수 있다.
     *
     * <p>{@code null}로 와도 빈 문자열로 저장한다. 설명이 없는 관계와 빈 설명을 구분할 이유가 없고,
     * 구분하면 화면이 두 경우를 따로 다뤄야 한다.
     */
    public record Relation(
            @JsonProperty("relation_key") String relationKey,
            @JsonProperty("target_document_id") UUID targetDocumentId,
            String description) {

        public Relation(String relationKey, UUID targetDocumentId) {
            this(relationKey, targetDocumentId, "");
        }

        public String descriptionOrEmpty() {
            return description == null ? "" : description;
        }
    }
}
