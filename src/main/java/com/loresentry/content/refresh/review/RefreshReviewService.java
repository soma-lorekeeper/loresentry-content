package com.loresentry.content.refresh.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.loresentry.content.document.DocumentSnapshot;
import com.loresentry.content.document.RefreshDocuments;
import com.loresentry.content.web.ContentFailure;
import com.loresentry.content.refresh.DraftSnapshot;
import com.loresentry.content.refresh.DraftSnapshots;
import com.loresentry.content.refresh.GraphRefreshRepository;
import com.loresentry.content.refresh.RefreshDraftRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최신화 조회(폴링·검토)와 작업본 자동 저장.
 *
 * <p>화면은 요청 뒤 {@link #latest} 를 몇 초마다 읽다가 READY 가 되면 {@link #detail} 로 작업본을 연다. 사용자가 양쪽을
 * 고칠 때마다 {@link #saveDraft} 로 저장한다(설계 §7.5). 모든 작업본이 맞춰지면 확정한다(confirm 패키지).
 */
@Service
public class RefreshReviewService {

    private static final int TITLE_MAX = 255;

    private final GraphRefreshRepository runs;

    private final RefreshDraftRepository drafts;

    private final RefreshDocuments documents;

    RefreshReviewService(GraphRefreshRepository runs, RefreshDraftRepository drafts, RefreshDocuments documents) {
        this.runs = runs;
        this.drafts = drafts;
        this.documents = documents;
    }

    /** 이 프로젝트의 가장 최근 최신화. 한 번도 하지 않았으면 {@code REFRESH_NOT_FOUND}. */
    @Transactional(readOnly = true)
    public RefreshResponses.Summary latest(UUID userId, UUID projectId) {
        requireProject(userId, projectId);
        GraphRefreshRepository.RunInfo run = runs.latestInfo(projectId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.REFRESH_NOT_FOUND));
        return new RefreshResponses.Summary(run.id(), run.status(), run.startedAt(), run.readyAt(), run.completedAt(),
                error(run), drafts.counts(run.id()));
    }

    /** run 하나와 열린 작업본 전부. 기준 버전 스냅샷은 한 번에 읽는다. */
    @Transactional(readOnly = true)
    public RefreshResponses.Detail detail(UUID userId, UUID projectId, UUID runId) {
        requireProject(userId, projectId);
        GraphRefreshRepository.RunInfo run = runs.findInfo(projectId, runId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.REFRESH_NOT_FOUND));
        List<RefreshDraftRepository.OpenDraft> open = drafts.openDrafts(run.id());

        Set<UUID> baseIds = new LinkedHashSet<>();
        open.forEach(draft -> {
            if (draft.baseVersionId() != null) {
                baseIds.add(draft.baseVersionId());
            }
        });
        Map<UUID, DocumentSnapshot> bases = documents.refreshBases(baseIds);

        List<RefreshResponses.Draft> items = new ArrayList<>();
        for (RefreshDraftRepository.OpenDraft draft : open) {
            RefreshResponses.DocumentRef document = draft.targetDocumentId() == null ? null
                    : new RefreshResponses.DocumentRef(draft.targetDocumentId(), draft.documentTitle(),
                            draft.documentFolder(), draft.documentRevision());
            RefreshResponses.Candidate candidate = draft.candidateId() == null ? null
                    : new RefreshResponses.Candidate(draft.candidateId(), draft.folderCode(),
                            candidateName(draft.candidateId(), draft.folderCode()));
            items.add(new RefreshResponses.Draft(draft.id(), draft.kind(), draft.draftRevision(), draft.matched(),
                    draft.updatedAt(), document, candidate, draft.baseVersionId(), draft.baseRevisionNo(),
                    draft.baseVersionId() == null ? null : bases.get(draft.baseVersionId()), draft.baseMismatch(),
                    draft.leftRevisionNo(), draft.left(), draft.right()));
        }
        return new RefreshResponses.Detail(run.id(), run.status(), run.startedAt(), run.readyAt(),
                run.completedAt(), error(run), drafts.counts(run.id()), items);
    }

    /**
     * 작업본 한 쪽 또는 양쪽을 저장한다. 받은 스냅샷은 문서 저장과 같은 규칙으로 확인하고 정규형으로 맞춘다
     * (관계 키는 서버가 다시 정하고, 관계는 대상마다 하나로 정렬한다). 정규형이라야 두 쪽이 같은지 DB 가 바르게 본다.
     *
     * <p>run 이 검토 중(READY)이어야 한다. 기존 문서 작업본은 두 쪽이 모두 있어야 하고, 새 문서 작업본은 비울 수 있다 —
     * 둘 다 비우면 "만들지 않음", 같은 내용이면 "그대로 만듦"이다.
     */
    @Transactional
    public RefreshResponses.SavedDraft saveDraft(UUID userId, UUID projectId, UUID runId, UUID draftId,
            RefreshResponses.DraftSave request) {
        requireProject(userId, projectId);
        String status = runs.statusForShare(projectId, runId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.REFRESH_NOT_FOUND));
        if (!"READY".equals(status)) {
            throw new ContentFailure(ContentFailure.Reason.REFRESH_NOT_READY);
        }
        RefreshDraftRepository.DraftHead draft = drafts.findOpen(runId, draftId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.REFRESH_DRAFT_NOT_FOUND));
        if (request == null || request.draftRevision() == null) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }
        boolean edit = "EDIT".equals(draft.kind());
        if (edit && (request.leftSnapshot() == null || request.rightSnapshot() == null)) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }

        // 자기 자신은 관계 대상이 될 수 없다. 기존 문서는 그 id, 새 문서는 그 임시 이름이다.
        String self = edit ? draft.targetDocumentId().toString() : draft.candidateId();
        Map<String, String> candidates = drafts.candidateFolders(runId);
        Map<String, String> relationKeys = documents.relationKeysByFolder();
        DraftSnapshot left = canonical(request.leftSnapshot(), projectId, self, candidates, relationKeys);
        DraftSnapshot right = canonical(request.rightSnapshot(), projectId, self, candidates, relationKeys);

        RefreshDraftRepository.Current saved = drafts.save(draftId, request.draftRevision(), left, right)
                .orElseThrow(() -> {
                    RefreshDraftRepository.Current current = drafts.current(draftId);
                    // 다른 탭·기기가 먼저 저장했다. 지금 모습을 돌려줘 화면이 다시 맞추게 한다.
                    Map<String, Object> latest = new LinkedHashMap<>();
                    latest.put("draft_revision", current.draftRevision());
                    latest.put("matched", current.matched());
                    latest.put("left_snapshot", current.left());
                    latest.put("right_snapshot", current.right());
                    return new ContentFailure(ContentFailure.Reason.REFRESH_DRAFT_CONFLICT,
                            Map.of("current", latest));
                });
        return new RefreshResponses.SavedDraft(draftId, saved.draftRevision(), saved.matched(), saved.updatedAt(),
                saved.left(), saved.right());
    }

    /**
     * 받은 스냅샷을 확인하고 정규형으로 바꾼다. 비어 있으면 그대로 null 이다(새 문서 작업본만 해당).
     *
     * @param self 이 작업본의 문서(자기 참조 관계를 막는다)
     */
    private DraftSnapshot canonical(DraftSnapshot snapshot, UUID projectId, String self,
            Map<String, String> candidates, Map<String, String> relationKeys) {
        if (snapshot == null) {
            return null;
        }
        String title = snapshot.title() == null ? "" : snapshot.title().trim();
        if (title.isEmpty() || title.length() > TITLE_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_FILE_TITLE);
        }
        // 옛 Markdown 문서는 에디터 본문 없이 Markdown 만 있다. 그 경우만 본문이 비어 있을 수 있다.
        if (snapshot.body() == null) {
            if (snapshot.legacyBodyMd() == null) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
            }
        } else {
            documents.requireValidBody(snapshot.body());
        }
        List<DocumentSnapshot.TextProperty> properties =
                snapshot.properties() == null ? List.of() : snapshot.properties();
        for (DocumentSnapshot.TextProperty property : properties) {
            if (property == null || property.key() == null || property.key().isBlank() || property.value() == null) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
            }
        }

        List<DraftSnapshot.Relation> relations = snapshot.relations() == null ? List.of() : snapshot.relations();
        Set<UUID> documentTargets = new LinkedHashSet<>();
        for (DraftSnapshot.Relation relation : relations) {
            if (relation == null || (relation.targetDocumentId() == null) == (relation.targetCandidateId() == null)) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
            }
            if (relation.targetDocumentId() != null) {
                documentTargets.add(relation.targetDocumentId());
            }
        }
        Map<UUID, RefreshDocuments.Live> targets = documents.read(projectId, documentTargets);
        List<DraftSnapshot.Relation> keyed = new ArrayList<>();
        for (DraftSnapshot.Relation relation : relations) {
            String folder;
            if (relation.targetDocumentId() != null) {
                RefreshDocuments.Live target = targets.get(relation.targetDocumentId());
                folder = target == null || target.trashed() ? null : target.folderCode();
            } else {
                folder = candidates.get(relation.targetCandidateId());
            }
            if (folder == null || relation.targetKey().equals(self)) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_RELATION_TARGET);
            }
            keyed.add(new DraftSnapshot.Relation(relationKeys.get(folder), relation.targetDocumentId(),
                    relation.targetCandidateId(), relation.description()));
        }
        return DraftSnapshots.withRelations(
                new DraftSnapshot(title, snapshot.body(), snapshot.legacyBodyMd(), properties, List.of()), keyed);
    }

    private void requireProject(UUID userId, UUID projectId) {
        if (!runs.ownsActiveProject(userId, projectId)) {
            throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
        }
    }

    private static RefreshResponses.Error error(GraphRefreshRepository.RunInfo run) {
        return run.errorCode() == null ? null : new RefreshResponses.Error(run.errorCode(), run.errorMessage());
    }

    /** {@code new:분류:이름} 에서 이름. 제안 그대로의 이름을 보여 준다. */
    private static String candidateName(String candidateId, String folderCode) {
        String prefix = "new:" + folderCode + ":";
        return candidateId.startsWith(prefix) ? candidateId.substring(prefix.length()) : candidateId;
    }
}
