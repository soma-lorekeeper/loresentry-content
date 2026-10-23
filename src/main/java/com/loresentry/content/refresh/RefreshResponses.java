package com.loresentry.content.refresh;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.content.document.DocumentSnapshot;

/** 최신화 조회·검토 API 의 응답 모양. */
public final class RefreshResponses {

    private RefreshResponses() {
    }

    /**
     * run 하나의 상태. 화면은 GENERATING 인 동안 이것을 몇 초마다 다시 읽고(폴링), 바뀌면 다음 화면으로 넘어간다.
     *
     * @param status       GENERATING(결과 대기) · READY(검토 중) · NO_CHANGE · FAILED · APPLIED
     * @param error        실패했을 때의 코드와 설명. 아니면 null
     * @param draftCounts  열린 작업본 수. READY 가 아니면 모두 0 이다
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(
            @JsonProperty("request_id") UUID requestId,
            String status,
            @JsonProperty("started_at") OffsetDateTime startedAt,
            @JsonProperty("ready_at") OffsetDateTime readyAt,
            @JsonProperty("completed_at") OffsetDateTime completedAt,
            Error error,
            @JsonProperty("draft_counts") Counts draftCounts) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Error(String code, String message) {
    }

    /**
     * @param total   열린 작업본 수
     * @param matched 두 쪽을 맞춘 작업본 수. total 과 같아야 확정할 수 있다
     * @param created 그중 새 설정 문서 제안 수
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Counts(long total, long matched, @JsonProperty("new") long created) {
    }

    /** 검토 화면이 읽는 run 과 그 작업본 전부. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(
            @JsonProperty("request_id") UUID requestId,
            String status,
            @JsonProperty("started_at") OffsetDateTime startedAt,
            @JsonProperty("ready_at") OffsetDateTime readyAt,
            @JsonProperty("completed_at") OffsetDateTime completedAt,
            Error error,
            @JsonProperty("draft_counts") Counts draftCounts,
            List<Draft> drafts) {
    }

    /**
     * 작업본 하나. left 는 지금 문서(사용자가 고칠 수 있다), right 는 AI 제안(역시 고칠 수 있다)이다. 두 쪽이 같아지면
     * {@code matched} 다. 기존 문서 작업본(EDIT)은 {@code document} 가, 새 설정 문서 작업본(NEW)은 {@code candidate}
     * 가 채워진다.
     *
     * @param draftRevision 자동 저장의 충돌 방지 번호. 저장할 때 그대로 돌려보낸다
     * @param baseSnapshot  요청 때 실은 문서의 기준 버전(AI 가 본 문서). graph-rag 가 더 읽은 문서와 새 문서는 null 이다
     * @param baseMismatch  AI 가 본 revision 과 left 의 revision 이 다르다(2-way 로만 비교할 수 있다)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Draft(
            UUID id,
            String kind,
            @JsonProperty("draft_revision") long draftRevision,
            boolean matched,
            @JsonProperty("updated_at") OffsetDateTime updatedAt,
            DocumentRef document,
            Candidate candidate,
            @JsonProperty("base_version_id") UUID baseVersionId,
            @JsonProperty("base_revision_no") Long baseRevisionNo,
            @JsonProperty("base_snapshot") DocumentSnapshot baseSnapshot,
            @JsonProperty("base_mismatch") boolean baseMismatch,
            @JsonProperty("left_revision_no") Long leftRevisionNo,
            @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot,
            @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {
    }

    /** 작업본이 고치는 기존 문서. 이름은 지금 문서의 것이다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DocumentRef(
            UUID id,
            String title,
            @JsonProperty("folder_code") String folderCode,
            @JsonProperty("revision_no") long revisionNo) {
    }

    /** AI 가 제안한 새 설정 문서. {@code name} 은 제안 그대로의 이름이다(만들 제목은 right 의 title). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Candidate(
            @JsonProperty("candidate_id") String candidateId,
            @JsonProperty("folder_code") String folderCode,
            String name) {
    }

    /** 작업본 저장(PATCH)의 결과. 서버가 정규형으로 맞춘 두 쪽을 돌려준다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SavedDraft(
            UUID id,
            @JsonProperty("draft_revision") long draftRevision,
            boolean matched,
            @JsonProperty("updated_at") OffsetDateTime updatedAt,
            @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot,
            @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {
    }

    /** 작업본 저장 요청. {@code draftRevision} 은 마지막으로 읽은 값이다. 새 문서 작업본은 한쪽이나 둘 다 null 일 수 있다. */
    public record DraftSave(
            @JsonProperty("draft_revision") Long draftRevision,
            @JsonProperty("left_snapshot") DraftSnapshot leftSnapshot,
            @JsonProperty("right_snapshot") DraftSnapshot rightSnapshot) {
    }
}
