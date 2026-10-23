package com.loresentry.content.refresh.completion;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * graph-rag 의 최신화 결과 파일({@code refresh-result-v3})에서 작업본을 만드는 데 쓰는 부분.
 *
 * <p>파일에는 사실·근거·출처·실행 기록도 있지만 여기서는 읽지 않는다. 그것들은 원본 그대로 {@code refresh_results} 에
 * 남는다. 모르는 필드는 무시한다 — graph-rag 가 필드를 더해도 이 서비스가 멈추지 않게 한다.
 *
 * <p>id 는 문자열로 받는다. 관계 끝점은 기존 문서 id 이거나 새 설정 문서의 임시 이름({@code new:분류:이름})이다.
 *
 * @param outcome PROPOSED(제안 있음) · NO_CHANGE(바꿀 것 없음) · FAILED
 */
public record RefreshResult(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("project_id") String projectId,
        String outcome,
        @JsonProperty("document_proposals") List<DocumentProposal> documentProposals,
        @JsonProperty("new_document_proposals") List<NewDocumentProposal> newDocumentProposals,
        @JsonProperty("relation_proposals") List<RelationProposal> relationProposals,
        Failure error) {

    /** 이 서비스가 읽을 줄 아는 결과 판. */
    static final String SCHEMA_VERSION = "refresh-result-v3";

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /**
     * 기존 설정 문서의 수정안. {@code value} 는 고친 본문 전체(평문)다.
     *
     * @param baseRevisionNo graph-rag 가 읽은 그 문서의 revision
     */
    public record DocumentProposal(
            @JsonProperty("target_document_id") String targetDocumentId,
            @JsonProperty("base_revision_no") Long baseRevisionNo,
            String field,
            String value) {
    }

    /** 새 설정 문서 제안. {@code candidateId} 는 {@code new:분류:이름} 모양의 임시 이름이다. */
    public record NewDocumentProposal(
            @JsonProperty("candidate_id") String candidateId,
            @JsonProperty("folder_code") String folderCode,
            String name,
            @JsonProperty("body_text") String bodyText) {
    }

    /** 관계 추가 제안. 끝점의 revision 은 새 설정 문서 쪽이면 null 이다. */
    public record RelationProposal(
            @JsonProperty("document_id") String documentId,
            @JsonProperty("target_document_id") String targetDocumentId,
            @JsonProperty("base_revision_no") Long baseRevisionNo,
            @JsonProperty("target_base_revision_no") Long targetBaseRevisionNo,
            String operation,
            String description) {
    }

    /** graph-rag 가 결과에 남긴 실패 원인. */
    public record Failure(String code, String message) {
    }

    /** 결과 파일 바이트를 읽는다. JSON 이 아니면 JSON library 의 예외가 나간다. */
    static RefreshResult parse(byte[] json) {
        return JSON.readValue(json, RefreshResult.class);
    }

    List<DocumentProposal> documentProposalsOrEmpty() {
        return documentProposals == null ? List.of() : documentProposals;
    }

    List<NewDocumentProposal> newDocumentProposalsOrEmpty() {
        return newDocumentProposals == null ? List.of() : newDocumentProposals;
    }

    List<RelationProposal> relationProposalsOrEmpty() {
        return relationProposals == null ? List.of() : relationProposals;
    }
}
