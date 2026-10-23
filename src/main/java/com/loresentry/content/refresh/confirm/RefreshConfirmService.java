package com.loresentry.content.refresh.confirm;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.loresentry.content.document.RefreshDocuments;
import com.loresentry.content.event.FileChanged.ChangeType;
import com.loresentry.content.event.FileChanged.Field;
import com.loresentry.content.event.ProjectChangedMessages;
import com.loresentry.content.file.FileService;
import com.loresentry.content.outbox.OutboxRecord;
import com.loresentry.content.outbox.OutboxRepository;
import com.loresentry.content.project.ProjectActivity;
import com.loresentry.content.web.ContentFailure;
import com.loresentry.content.refresh.DraftSnapshot;
import com.loresentry.content.refresh.GraphRefreshRepository;
import com.loresentry.content.refresh.RefreshCleanup;
import com.loresentry.content.refresh.RefreshDraftRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검토를 마친 최신화를 확정한다. 모든 작업본이 맞춰졌을 때(두 쪽이 같을 때)만 한 트랜잭션으로 반영하고, 아니면 아무것도
 * 바꾸지 않고 이유를 알린다. 폐기·건너뛰기는 없다 — 제안을 버리려면 그 작업본의 right 를 left 와 같게 맞춘다
 * (새 문서 제안은 두 쪽을 비운다).
 *
 * <p>반영 순서:
 * <ol>
 *   <li>새 설정 문서를 만든다. 다른 작업본의 관계가 임시 이름으로 가리키므로 먼저다.
 *   <li>기존 문서의 제목·본문·속성을 쓴다(바뀐 것만, 요청·결과 때 확인한 revision 조건으로).
 *   <li>관계를 쌍 단위로 더하고 뺀다({@link RelationDelta}).
 *   <li>바뀐 문서마다 AI_APPLY 버전을 남기고, run 을 APPLIED 로 끝내며 작업본·기준 버전·잠금을 치운다.
 *   <li>FileChanged 를 outbox 에 한 번 쓴다(트랜잭션 끝에 한 번 — outbox 규칙).
 * </ol>
 *
 * <p>대상 문서들은 이 run 이 잠가 두었으므로 그 사이 편집·이름 변경·삭제가 없다. 그래도 어긋나 보이면(잠금이 없거나
 * revision 이 다름) 반영하지 않는다. 잠금은 READ COMMITTED 에서 id 순으로 {@code for no key update} 로 잡는다.
 */
@Service
public class RefreshConfirmService {

    private final GraphRefreshRepository runs;

    private final RefreshDraftRepository drafts;

    private final RefreshDocuments documents;

    private final FileService files;

    private final ProjectActivity activity;

    private final OutboxRepository outbox;

    private final ProjectChangedMessages messages;

    private final RefreshCleanup cleanup;

    RefreshConfirmService(GraphRefreshRepository runs, RefreshDraftRepository drafts, RefreshDocuments documents,
            FileService files, ProjectActivity activity, OutboxRepository outbox, ProjectChangedMessages messages,
            RefreshCleanup cleanup) {
        this.runs = runs;
        this.drafts = drafts;
        this.documents = documents;
        this.files = files;
        this.activity = activity;
        this.outbox = outbox;
        this.messages = messages;
        this.cleanup = cleanup;
    }

    @Transactional
    public ConfirmResponses.Confirmed confirm(UUID userId, UUID projectId, UUID runId) {
        if (!runs.ownsActiveProject(userId, projectId)) {
            throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
        }
        GraphRefreshRepository.RunRow run = runs.lockRun(runId)
                .filter(row -> row.projectId().equals(projectId))
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.REFRESH_NOT_FOUND));
        if (!"READY".equals(run.status())) {
            throw new ContentFailure(ContentFailure.Reason.REFRESH_NOT_READY);
        }

        List<RefreshDraftRepository.ConfirmDraft> open = drafts.openForConfirm(runId);
        List<String> unmatched = open.stream().filter(draft -> !draft.matched())
                .map(draft -> draft.id().toString()).toList();
        if (!unmatched.isEmpty()) {
            throw new ContentFailure(ContentFailure.Reason.REFRESH_DRAFTS_UNMATCHED, Map.of("drafts", unmatched));
        }
        List<RefreshDraftRepository.ConfirmDraft> edits = open.stream()
                .filter(draft -> "EDIT".equals(draft.kind())).toList();
        // right 가 비어 있는 새 문서 제안은 "만들지 않음"으로 맞춘 것이다.
        List<RefreshDraftRepository.ConfirmDraft> accepted = open.stream()
                .filter(draft -> "NEW".equals(draft.kind()) && draft.right() != null).toList();
        Set<String> declined = new LinkedHashSet<>();
        open.stream().filter(draft -> "NEW".equals(draft.kind()) && draft.right() == null)
                .forEach(draft -> declined.add(draft.candidateId()));

        Map<UUID, RefreshDocuments.Locked> locked = documents.lockForApply(
                edits.stream().map(RefreshDraftRepository.ConfirmDraft::targetDocumentId).toList());
        requireUnchanged(runId, edits, locked);

        RelationDelta.Plan plan = RelationDelta.merge(sides(edits, accepted));
        requireApplicable(projectId, edits, accepted, declined, plan);

        // 1. 새 설정 문서. 임시 이름 순으로 만들어 결과가 매번 같게 한다.
        Map<String, UUID> createdIds = new LinkedHashMap<>();
        List<ConfirmResponses.Created> created = new ArrayList<>();
        for (RefreshDraftRepository.ConfirmDraft draft : accepted.stream()
                .sorted((a, b) -> a.candidateId().compareTo(b.candidateId())).toList()) {
            DraftSnapshot right = draft.right();
            UUID fileId = files.createSettingDocument(projectId, draft.folderCode(), right.title());
            writeOrTitleTaken(fileId, 0, right);
            createdIds.put(draft.candidateId(), fileId);
            created.add(new ConfirmResponses.Created(draft.id(), draft.candidateId(), fileId));
        }

        // 2. 기존 문서의 제목·본문·속성. 바뀐 칸은 이벤트에 싣는다.
        Map<UUID, Set<Field>> fields = new LinkedHashMap<>();
        Set<UUID> written = new LinkedHashSet<>();
        for (RefreshDraftRepository.ConfirmDraft draft : edits) {
            Set<Field> changed = EnumSet.noneOf(Field.class);
            if (contentChanged(draft)) {
                RefreshDocuments.Written result = writeOrTitleTaken(draft.targetDocumentId(), draft.leftRevisionNo(),
                        draft.right());
                written.add(draft.targetDocumentId());
                if (result.titleChanged()) {
                    changed.add(Field.TITLE);
                }
                if (result.bodyChanged()) {
                    changed.add(Field.BODY);
                }
                if (result.propertiesChanged()) {
                    changed.add(Field.PROPERTIES);
                }
            }
            fields.put(draft.targetDocumentId(), changed);
        }

        // 3. 관계. 임시 이름은 방금 만든 문서 id 로 바꾼다. 관계 이벤트는 그 행을 가진 문서(low)의 것이다.
        Set<UUID> relationOwners = new LinkedHashSet<>();
        Set<UUID> relationTouched = new LinkedHashSet<>();
        for (RelationDelta.Op op : plan.ops()) {
            UUID one = resolve(op.one(), createdIds);
            UUID other = resolve(op.other(), createdIds);
            (op.put() ? documents.putRelation(one, other, op.description()) : documents.removeRelation(one, other))
                    .ifPresent(owner -> {
                        relationOwners.add(owner);
                        relationTouched.add(one);
                        relationTouched.add(other);
                    });
        }

        // 4. 버전·작업 시각·정리.
        createdIds.values().forEach(fileId -> documents.recordAiApply(projectId, fileId));
        written.forEach(fileId -> documents.recordAiApply(projectId, fileId));
        activity.touch(projectId);
        cleanup.release(runId);
        runs.markEnded(runId, "APPLIED", null, null);

        // 5. 이벤트. 새 문서는 CREATED(모든 칸)로 충분하다 — 관계도 그 스냅샷에 들어 있다.
        List<OutboxRecord> events = new ArrayList<>();
        createdIds.values().forEach(fileId -> events.add(messages.fileChanged(projectId, fileId,
                ChangeType.CREATED, EnumSet.allOf(Field.class))));
        Set<UUID> updatedFiles = new LinkedHashSet<>();
        fields.forEach((fileId, changed) -> {
            if (relationOwners.contains(fileId)) {
                changed.add(Field.RELATIONS);
            }
            if (!changed.isEmpty()) {
                events.add(messages.fileChanged(projectId, fileId, ChangeType.UPDATED, changed));
                updatedFiles.add(fileId);
            }
        });
        for (UUID owner : relationOwners) {
            if (!createdIds.containsValue(owner) && !fields.containsKey(owner)) {
                events.add(messages.fileChanged(projectId, owner, ChangeType.UPDATED, EnumSet.of(Field.RELATIONS)));
            }
        }
        outbox.append(events.toArray(OutboxRecord[]::new));

        List<ConfirmResponses.Updated> updated = new ArrayList<>();
        List<UUID> unchanged = new ArrayList<>();
        for (RefreshDraftRepository.ConfirmDraft draft : edits) {
            UUID fileId = draft.targetDocumentId();
            if (written.contains(fileId) || relationTouched.contains(fileId) || updatedFiles.contains(fileId)) {
                long revision = locked.get(fileId).revisionNo() + (written.contains(fileId) ? 1 : 0);
                updated.add(new ConfirmResponses.Updated(draft.id(), fileId, revision));
            } else {
                unchanged.add(draft.id());
            }
        }
        open.stream().filter(draft -> "NEW".equals(draft.kind()) && draft.right() == null)
                .forEach(draft -> unchanged.add(draft.id()));
        return new ConfirmResponses.Confirmed(runId, "APPLIED", created, updated, unchanged);
    }

    /**
     * 대상 문서가 이 run 의 잠금 아래 작업본을 뜰 때 그대로인지 확인한다. 잠금 때문에 정상 흐름에서는 바뀔 수 없지만,
     * 어긋나 보이면 덮어쓰지 않는다.
     */
    private static void requireUnchanged(UUID runId, List<RefreshDraftRepository.ConfirmDraft> edits,
            Map<UUID, RefreshDocuments.Locked> locked) {
        List<Map<String, String>> changed = new ArrayList<>();
        for (RefreshDraftRepository.ConfirmDraft draft : edits) {
            RefreshDocuments.Locked document = locked.get(draft.targetDocumentId());
            if (document == null || document.trashed() || document.userLocked()
                    || !runId.equals(document.refreshLockRunId())
                    || !Objects.equals(document.revisionNo(), draft.leftRevisionNo())) {
                changed.add(Map.of("draft_id", draft.id().toString(),
                        "file_id", draft.targetDocumentId().toString()));
            }
        }
        if (!changed.isEmpty()) {
            throw new ContentFailure(ContentFailure.Reason.REFRESH_DOCUMENTS_CHANGED, Map.of("documents", changed));
        }
    }

    /** 작업본마다 관계 결정: 기존 문서는 처음 뜬 관계 대비, 받아들인 새 문서는 빈 목록 대비. */
    private static List<RelationDelta.Side> sides(List<RefreshDraftRepository.ConfirmDraft> edits,
            List<RefreshDraftRepository.ConfirmDraft> accepted) {
        List<RelationDelta.Side> sides = new ArrayList<>();
        edits.forEach(draft -> sides.add(new RelationDelta.Side(draft.targetDocumentId().toString(),
                draft.origin().relations(), draft.right().relations())));
        accepted.forEach(draft -> sides.add(new RelationDelta.Side(draft.candidateId(), List.of(),
                draft.right().relations())));
        return sides;
    }

    /**
     * 쓰기 전에 그대로 반영할 수 있는지 모두 확인한다. 하나라도 안 되면 무엇이 문제인지 목록으로 알리고 아무것도 쓰지
     * 않는다.
     */
    private void requireApplicable(UUID projectId, List<RefreshDraftRepository.ConfirmDraft> edits,
            List<RefreshDraftRepository.ConfirmDraft> accepted, Set<String> declined, RelationDelta.Plan plan) {
        List<Map<String, String>> problems = new ArrayList<>();
        plan.conflicts().forEach(conflict -> problems.add(Map.of("code", "RELATION_CONFLICT",
                "one", conflict.one(), "other", conflict.other())));

        Set<String> acceptedCandidates = new LinkedHashSet<>();
        accepted.forEach(draft -> acceptedCandidates.add(draft.candidateId()));
        Set<UUID> documentEnds = new LinkedHashSet<>();
        for (RelationDelta.Op op : plan.ops()) {
            if (!op.put()) {
                continue;
            }
            for (String end : List.of(op.one(), op.other())) {
                if (declined.contains(end)) {
                    problems.add(Map.of("code", "REJECTED_CANDIDATE", "target", end));
                } else if (end.startsWith("new:")) {
                    if (!acceptedCandidates.contains(end)) {
                        problems.add(Map.of("code", "INVALID_RELATION_TARGET", "target", end));
                    }
                } else {
                    documentEnds.add(UUID.fromString(end));
                }
            }
        }
        Map<UUID, RefreshDocuments.Live> ends = documents.read(projectId, documentEnds);
        for (UUID end : documentEnds) {
            RefreshDocuments.Live document = ends.get(end);
            if (document == null || document.trashed()) {
                problems.add(Map.of("code", "INVALID_RELATION_TARGET", "target", end.toString()));
            }
        }

        // 옛 Markdown 문서는 에디터 본문이 없어 제목·속성만 바꿔 쓸 수 없다(문서 저장도 본문을 함께 쓴다).
        for (RefreshDraftRepository.ConfirmDraft draft : edits) {
            if (contentChanged(draft) && draft.right().body() == null) {
                problems.add(Map.of("code", "BODY_REQUIRED", "draft_id", draft.id().toString()));
            }
        }
        if (!problems.isEmpty()) {
            throw new ContentFailure(ContentFailure.Reason.REFRESH_DRAFTS_INVALID, Map.of("problems", problems));
        }
    }

    /** 처음 뜬 상태와 제목·본문·속성이 다른가. 관계는 따로 쌍 단위로 다룬다. */
    private static boolean contentChanged(RefreshDraftRepository.ConfirmDraft draft) {
        DraftSnapshot origin = draft.origin();
        DraftSnapshot agreed = draft.right();
        return !Objects.equals(origin.title(), agreed.title())
                || !Objects.equals(origin.body(), agreed.body())
                || !Objects.equals(origin.legacyBodyMd(), agreed.legacyBodyMd())
                || !Objects.equals(origin.properties(), agreed.properties());
    }

    /** 문서 쓰기. 같은 자리에 같은 제목이 있으면 문서 저장과 같은 오류로 알린다(트랜잭션 전체가 되돌려진다). */
    private RefreshDocuments.Written writeOrTitleTaken(UUID fileId, long expectedRevision, DraftSnapshot agreed) {
        try {
            return documents.write(fileId, expectedRevision, agreed.title(), agreed.body(), agreed.properties());
        } catch (DuplicateKeyException taken) {
            throw new ContentFailure(ContentFailure.Reason.FILE_TITLE_TAKEN);
        }
    }

    private static UUID resolve(String end, Map<String, UUID> createdIds) {
        UUID created = createdIds.get(end);
        return created != null ? created : UUID.fromString(end);
    }
}
