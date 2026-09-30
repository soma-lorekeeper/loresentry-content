package com.loresentry.content.graph;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 프로젝트의 관계 투영본.
 *
 * <p>이것은 graph-rag 가 만드는 것과 다르다. graph-rag 는 AI 가 본문에서 찾아낸 관계를 Neptune 에
 * 쌓고 여러 홉을 걸어간다. 여기서 주는 것은 <b>사용자가 직접 이어 놓은 관계</b>뿐이고, 전부
 * content 의 RDB 세 표에 이미 있다 — {@code document}, {@code document_properties},
 * {@code document_relations}. 한 홉이므로 질의 세 번으로 끝난다.
 *
 * <p>그래서 타임라인과 관계도는 graph-rag 를 기다릴 이유가 없다. 나중에 AI 관계가 붙으면 그때
 * 여기에 출처를 더한다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GraphResponses() {

    public record Node(
            UUID id,
            String title,
            @JsonProperty("folder_code") String folderCode,
            String description) {
    }

    public record Edge(
            UUID id,
            UUID source,
            UUID target,
            @JsonProperty("relation_key") String relationKey,
            String description) {
    }

    /** 회차 목록은 원고 순서다. 타임라인의 행 순서가 여기서 나온다. */
    public record Episode(
            UUID id,
            String name,
            @JsonProperty("document_ids") List<UUID> documentIds) {
    }

    public record Graph(List<Node> nodes, List<Edge> edges, List<Episode> episodes) {
    }
}
