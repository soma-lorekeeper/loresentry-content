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

    /**
     * 연결 하나. 저장은 양방향 두 행이지만 여기서는 **한 쌍에 하나**만 준다 — 같은 연결을 두 번 주면
     * 관계도에 링크가 두 개 그려지고 힘이 두 배로 걸린다.
     *
     * <p>{@code origin} 은 이 관계를 누가 만들었는지다. 지금은 모두 {@code USER} 다. graph-rag 가
     * 본문에서 찾아낸 관계를 더할 때 {@code AI} 가 생기며, <b>그때 응답 모양은 바뀌지 않는다</b> —
     * 화면이 출처를 구분해 표시할 수 있도록 지금부터 자리를 둔다.
     */
    public record Edge(
            UUID id,
            UUID source,
            UUID target,
            @JsonProperty("relation_key") String relationKey,
            String description,
            String origin) {
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
