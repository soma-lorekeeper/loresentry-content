package com.loresentry.content.refresh.completion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.loresentry.content.document.RefreshDocuments;
import com.loresentry.content.refresh.DraftSnapshot;
import com.loresentry.content.refresh.DraftSnapshots;
import com.loresentry.content.refresh.RefreshDraftRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * graph-rag 의 제안(결과 파일)으로 검토 작업본을 연다. 완료 반영({@link GraphRefreshCompletionService#complete})이 run 을 잠근
 * 트랜잭션 안에서 부른다.
 *
 * <p>작업본은 문서마다 하나다. left 는 지금 문서, right 는 left 에 제안을 더한 것이다.
 * <ul>
 *   <li><b>요청 때 실은 문서</b>: 요청 때 만든 작업본(left 와 기준 버전이 있다)에 right 를 채운다. 그 문서는 요청 때부터
 *       잠겨 있어 left 가 곧 graph-rag 가 본 문서다.
 *   <li><b>graph-rag 가 목록 API 로 더 읽은 문서</b>: 지금 문서를 left 로 새 작업본을 만들고 이 run 으로 잠근다. 기준
 *       버전은 없다. graph-rag 가 본 뒤 문서가 바뀌었으면 {@code base_mismatch} 로 알린다.
 *   <li><b>새 설정 문서 제안</b>: left 없이 right 만 있는 작업본(NEW)을 만든다.
 *   <li><b>관계 제안</b>: 관계는 두 문서가 함께 가진다(한 쌍 한 행). 그래서 기존 문서인 양 끝의 작업본 모두에 넣는다 —
 *       한쪽에만 넣으면 다른 쪽을 반영할 때 관계 목록을 맞추며 지워진다. 새 설정 문서 끝은 임시 이름으로 가리킨다.
 * </ul>
 *
 * <p>쓸 수 없는 제안은 조용히 버린다(원본은 {@code refresh_results} 에 남는다): 휴지통·없는 문서, 사용자가 잠근 문서,
 * 원고 본문 수정, 모양이 틀린 새 문서. 제안이 하나도 남지 않은 작업본은 기준 버전과 함께 지운다.
 */
@Component
class RefreshDraftBuilder {

    private static final Logger log = LoggerFactory.getLogger(RefreshDraftBuilder.class);

    /** 원고 폴더. 원고 본문은 최신화가 고치지 않는다(설정 문서만 고친다). 관계 끝으로는 올 수 있다. */
    private static final String MANUSCRIPT = "MANUSCRIPT";

    /** 문서 제목 한도. 문서 저장과 같다. */
    private static final int TITLE_MAX = 255;

    private final RefreshDraftRepository drafts;

    private final RefreshDocuments documents;

    RefreshDraftBuilder(RefreshDraftRepository drafts, RefreshDocuments documents) {
        this.drafts = drafts;
        this.documents = documents;
    }

    /** 걸러 낸 관계 제안 하나. 끝점은 기존 문서 id 문자열이거나 새 설정 문서 임시 이름이다. */
    private record Link(String one, String other, Long oneRevision, Long otherRevision, String description) {

        boolean touches(String endpoint) {
            return one.equals(endpoint) || other.equals(endpoint);
        }

        String opposite(String endpoint) {
            return one.equals(endpoint) ? other : one;
        }

        /** 이 끝에 대해 graph-rag 가 본 revision. 새 설정 문서 끝이면 null 이다. */
        Long revisionOf(String endpoint) {
            return one.equals(endpoint) ? oneRevision : otherRevision;
        }
    }

    /**
     * @return 검토할 작업본을 하나라도 열었는가. 아니면 부르는 쪽이 run 을 "변경 없음"으로 끝낸다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean build(UUID runId, UUID projectId, RefreshResult result) {
        Map<String, String> relationKeys = documents.relationKeysByFolder();

        // 새 설정 문서 제안: 임시 이름이 "new:분류:이름" 과 맞고, 설정 폴더이며, 이름이 제목으로 쓸 수 있어야 한다.
        Map<String, RefreshResult.NewDocumentProposal> candidates = new LinkedHashMap<>();
        for (RefreshResult.NewDocumentProposal proposal : result.newDocumentProposalsOrEmpty()) {
            if (usableCandidate(proposal, relationKeys)) {
                candidates.putIfAbsent(proposal.candidateId(), proposal);
            }
        }

        // 제안이 가리키는 기존 문서를 한 번에 읽는다.
        Set<UUID> referenced = new LinkedHashSet<>();
        for (RefreshResult.DocumentProposal proposal : result.documentProposalsOrEmpty()) {
            addIfDocument(proposal.targetDocumentId(), referenced);
        }
        for (RefreshResult.RelationProposal proposal : result.relationProposalsOrEmpty()) {
            addIfDocument(proposal.documentId(), referenced);
            addIfDocument(proposal.targetDocumentId(), referenced);
        }
        Map<UUID, RefreshDocuments.Live> live = documents.read(projectId, referenced);

        // 본문 수정안: 고칠 수 있는 설정 문서만. 같은 문서에 둘이 오면 첫 것을 쓴다.
        Map<UUID, RefreshResult.DocumentProposal> edits = new LinkedHashMap<>();
        for (RefreshResult.DocumentProposal proposal : result.documentProposalsOrEmpty()) {
            UUID target = uuidOrNull(proposal.targetDocumentId());
            RefreshDocuments.Live document = target == null ? null : live.get(target);
            if (!"body_text".equals(proposal.field()) || proposal.value() == null || !editable(document)
                    || MANUSCRIPT.equals(document.folderCode())) {
                continue;
            }
            edits.putIfAbsent(target, proposal);
        }

        // 관계 추가안: 두 끝 모두 쓸 수 있어야 한다.
        List<Link> links = new ArrayList<>();
        for (RefreshResult.RelationProposal proposal : result.relationProposalsOrEmpty()) {
            String one = proposal.documentId();
            String other = proposal.targetDocumentId();
            if (!"ADD".equals(proposal.operation()) || one == null || other == null || one.equals(other)
                    || !usableEndpoint(one, live, candidates) || !usableEndpoint(other, live, candidates)) {
                continue;
            }
            links.add(new Link(one, other, proposal.baseRevisionNo(), proposal.targetBaseRevisionNo(),
                    proposal.description() == null ? "" : proposal.description()));
        }

        // 기존 문서 작업본 대상과, 그 문서에 대해 graph-rag 가 본 revision 들.
        Map<UUID, List<Long>> seenRevisions = new LinkedHashMap<>();
        edits.forEach((target, proposal) ->
                seenRevisions.computeIfAbsent(target, id -> new ArrayList<>()).add(proposal.baseRevisionNo()));
        for (Link link : links) {
            for (String endpoint : List.of(link.one(), link.other())) {
                UUID document = candidates.containsKey(endpoint) ? null : uuidOrNull(endpoint);
                if (document != null) {
                    seenRevisions.computeIfAbsent(document, id -> new ArrayList<>()).add(link.revisionOf(endpoint));
                }
            }
        }

        Map<UUID, RefreshDraftRepository.BaseDraft> captured = drafts.baseDrafts(runId);
        List<UUID> newlyLocked = new ArrayList<>();
        int opened = 0;
        for (Map.Entry<UUID, List<Long>> entry : seenRevisions.entrySet()) {
            UUID target = entry.getKey();
            RefreshDraftRepository.BaseDraft base = captured.get(target);
            RefreshDocuments.Live document = live.get(target);
            DraftSnapshot left = base != null ? base.left() : DraftSnapshots.of(document.snapshot());
            long leftRevision = base != null ? base.leftRevisionNo() : document.revisionNo();
            boolean mismatch = entry.getValue().stream()
                    .anyMatch(seen -> seen != null && seen != leftRevision);
            DraftSnapshot right = proposed(left, edits.get(target), target.toString(), links, live, candidates,
                    relationKeys);
            if (base != null) {
                drafts.open(base.id(), right, mismatch);
            } else {
                drafts.insertOpenEdit(runId, target, leftRevision, left, right, mismatch);
                newlyLocked.add(target);
            }
            opened++;
        }
        // 새로 작업본이 생긴 문서도 검토가 끝날 때까지 잠근다(요청 때 실은 문서는 이미 잠겨 있다).
        documents.lock(runId, newlyLocked);

        for (RefreshResult.NewDocumentProposal candidate : candidates.values()) {
            List<DraftSnapshot.Relation> relations = new ArrayList<>();
            for (Link link : links) {
                if (link.touches(candidate.candidateId())) {
                    relations.add(relationTo(link.opposite(candidate.candidateId()), link.description(), live,
                            candidates, relationKeys));
                }
            }
            DraftSnapshot right = DraftSnapshots.withRelations(new DraftSnapshot(candidate.name().trim(),
                    documents.bodyFromText(candidate.bodyText(), null), null, List.of(), List.of()), relations);
            drafts.insertNew(runId, candidate.candidateId(), candidate.folderCode(), right);
            opened++;
        }

        // 결과에 제안이 없는 문서의 작업본은 검토할 것이 없다. 기준 버전과 함께 지운다(잠금은 run 이 끝날 때 푼다).
        documents.deleteRefreshBases(drafts.deleteUnopened(runId));

        int dropped = result.documentProposalsOrEmpty().size() - edits.size()
                + result.relationProposalsOrEmpty().size() - links.size()
                + result.newDocumentProposalsOrEmpty().size() - candidates.size();
        if (dropped > 0) {
            log.info("Graph refresh {} skipped {} proposal(s) it cannot apply", runId, dropped);
        }
        return opened > 0;
    }

    /**
     * left 에 이 문서의 제안을 더한 right. 본문 수정안이 있으면 본문을 바꾸고(바뀌지 않은 줄은 서식 유지), 이 문서가
     * 끝인 관계 제안을 관계 목록에 더한다. 제목·속성은 그대로다.
     */
    private DraftSnapshot proposed(DraftSnapshot left, RefreshResult.DocumentProposal edit, String self,
            List<Link> links, Map<UUID, RefreshDocuments.Live> live,
            Map<String, RefreshResult.NewDocumentProposal> candidates, Map<String, String> relationKeys) {
        JsonNode body = left.body();
        String legacyBodyMd = left.legacyBodyMd();
        if (edit != null) {
            // 옛 Markdown 문서면 기준 본문이 없어 모든 줄이 새 문단이 된다. 고친 본문은 에디터 본문이다.
            body = documents.bodyFromText(edit.value(), left.body());
            legacyBodyMd = null;
        }
        List<DraftSnapshot.Relation> relations = new ArrayList<>(left.relations());
        for (Link link : links) {
            if (link.touches(self)) {
                relations.add(relationTo(link.opposite(self), link.description(), live, candidates, relationKeys));
            }
        }
        return DraftSnapshots.withRelations(
                new DraftSnapshot(left.title(), body, legacyBodyMd, left.properties(), List.of()), relations);
    }

    /** 관계 대상 하나. 관계 키는 대상 문서(또는 새 문서 제안)의 분류에서 정한다. */
    private static DraftSnapshot.Relation relationTo(String target, String description,
            Map<UUID, RefreshDocuments.Live> live, Map<String, RefreshResult.NewDocumentProposal> candidates,
            Map<String, String> relationKeys) {
        RefreshResult.NewDocumentProposal candidate = candidates.get(target);
        if (candidate != null) {
            return new DraftSnapshot.Relation(relationKeys.get(candidate.folderCode()), null, target, description);
        }
        UUID id = UUID.fromString(target);
        return new DraftSnapshot.Relation(relationKeys.get(live.get(id).folderCode()), id, null, description);
    }

    /** 고칠 수 있는 문서: 있고, 휴지통이 아니고, 사용자가 잠그지 않았다. */
    private static boolean editable(RefreshDocuments.Live document) {
        return document != null && !document.trashed() && !document.userLocked();
    }

    private static boolean usableEndpoint(String endpoint, Map<UUID, RefreshDocuments.Live> live,
            Map<String, RefreshResult.NewDocumentProposal> candidates) {
        if (candidates.containsKey(endpoint)) {
            return true;
        }
        UUID id = uuidOrNull(endpoint);
        return id != null && editable(live.get(id));
    }

    private static boolean usableCandidate(RefreshResult.NewDocumentProposal proposal,
            Map<String, String> relationKeys) {
        String name = proposal.name() == null ? "" : proposal.name().trim();
        return proposal.candidateId() != null && proposal.folderCode() != null && proposal.bodyText() != null
                && relationKeys.containsKey(proposal.folderCode()) && !MANUSCRIPT.equals(proposal.folderCode())
                && !name.isEmpty() && name.length() <= TITLE_MAX
                && proposal.candidateId().equals("new:" + proposal.folderCode() + ":" + proposal.name());
    }

    private static void addIfDocument(String id, Set<UUID> ids) {
        UUID parsed = uuidOrNull(id);
        if (parsed != null) {
            ids.add(parsed);
        }
    }

    private static UUID uuidOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }
}
