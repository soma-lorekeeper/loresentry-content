package com.loresentry.content.document;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
        @JsonProperty("body_md") String bodyMd,
        List<TextProperty> properties,
        List<Relation> relations) {

    public record TextProperty(String key, String value) {
    }

    public record Relation(
            @JsonProperty("relation_key") String relationKey,
            @JsonProperty("target_document_id") UUID targetDocumentId) {
    }
}
