package com.loresentry.content.refresh;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.content.document.DocumentSnapshot;

import tools.jackson.databind.JsonNode;

/**
 * 검토 작업본 한 쪽(left 또는 right)의 문서. 저장·버전 스냅샷({@link DocumentSnapshot})과 같은 모양이고, 관계가
 * 아직 없는 새 설정 문서(graph-rag 의 candidate)를 가리킬 수 있다는 점만 다르다.
 *
 * <p>DB 에는 이 모양 그대로 jsonb 로 들어가고, 두 쪽이 같은지(matched)를 DB 가 jsonb 비교로 정한다. 그래서 서버는
 * 저장 전에 항상 {@link DraftSnapshots} 로 정규화한다 — 같은 내용이 관계 순서만 달라 다른 것으로 보이면 안 된다.
 *
 * @param legacyBodyMd 변환 전 Markdown 본문. 아직 에디터 본문으로 바뀌지 않은 옛 문서에만 있다
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DraftSnapshot(
        String title,
        JsonNode body,
        @JsonProperty("legacy_body_md") String legacyBodyMd,
        List<DocumentSnapshot.TextProperty> properties,
        List<Relation> relations) {

    /**
     * 관계 한 줄. 대상은 기존 문서({@code targetDocumentId}) 이거나 이번 최신화가 제안한 새 설정 문서
     * ({@code targetCandidateId}) 둘 중 하나다. 새 문서는 확정할 때 만들어지고, 그때 실제 id 로 바뀐다.
     *
     * @param relationKey 대상 문서 분류에서 정해진다. 서버가 정규화할 때 다시 계산하므로 보내온 값은 쓰지 않는다
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Relation(
            @JsonProperty("relation_key") String relationKey,
            @JsonProperty("target_document_id") UUID targetDocumentId,
            @JsonProperty("target_candidate_id") String targetCandidateId,
            String description) {

        /** 대상을 하나의 문자열로. 정렬·중복 제거·관계 쌍 비교의 열쇠다. */
        public String targetKey() {
            return targetDocumentId != null ? targetDocumentId.toString() : targetCandidateId;
        }
    }
}
