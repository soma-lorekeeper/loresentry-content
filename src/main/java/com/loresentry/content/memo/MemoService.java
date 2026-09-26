package com.loresentry.content.memo;

import java.util.UUID;

import com.loresentry.content.project.ProjectActivity;
import com.loresentry.content.web.ContentFailure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoService {

    static final int BODY_MAX = 20_000;

    static final int TITLE_MAX = 200;

    private final MemoRepository repository;

    private final ProjectActivity activity;

    public MemoService(MemoRepository repository, ProjectActivity activity) {
        this.repository = repository;
        this.activity = activity;
    }

    @Transactional(readOnly = true)
    public MemoDtos.MemoList list(UUID ownerUserId, UUID projectId, String scope, UUID documentId) {
        requireProject(ownerUserId, projectId);

        return new MemoDtos.MemoList(switch (normalizeScope(scope)) {
            case "PROJECT" -> repository.listProjectMemos(projectId);
            case "FILE" -> {
                if (documentId == null) {
                    throw new ContentFailure(ContentFailure.Reason.INVALID_MEMO);
                }
                yield repository.listFileMemos(projectId, documentId);
            }
            default -> throw new ContentFailure(ContentFailure.Reason.INVALID_MEMO);
        });
    }

    @Transactional
    public MemoDtos.Memo create(UUID ownerUserId, UUID projectId, MemoDtos.CreateRequest request) {
        requireProject(ownerUserId, projectId);
        String scope = normalizeScope(request.scope());
        String title = validateTitle(request.title());
        String body = validateBody(request.body());

        UUID documentId = null;
        if ("FILE".equals(scope)) {
            if (request.documentId() == null
                    || !repository.activeDocumentInProject(projectId, request.documentId())) {
                // 휴지통 문서나 남의 문서에 메모를 달면 어디에도 보이지 않는 메모가 생긴다.
                throw new ContentFailure(ContentFailure.Reason.INVALID_MEMO);
            }
            documentId = request.documentId();
        }

        MemoDtos.Memo memo = repository.insert(projectId, scope, documentId, title, body);
        activity.touch(projectId);
        return memo;
    }

    /**
     * 본문 전체를 덮어쓴다. 버전 충돌을 검사하지 않는다 — 메모는 한 사람이 한 칸에 쓰는 짧은 글이고,
     * 문서 본문처럼 병합할 구조가 없다. 마지막 저장이 이긴다(프론트 가정과 같다).
     */
    @Transactional
    public MemoDtos.Memo update(UUID ownerUserId, UUID memoId, MemoDtos.UpdateRequest request) {
        MemoDtos.Memo memo = require(ownerUserId, memoId);
        String title = request.title() == null ? memo.title() : validateTitle(request.title());
        String body = request.body() == null ? memo.body() : validateBody(request.body());

        MemoDtos.Memo updated = repository.update(memoId, title, body);
        activity.touch(memo.projectId());
        return updated;
    }

    @Transactional
    public void delete(UUID ownerUserId, UUID memoId) {
        MemoDtos.Memo memo = require(ownerUserId, memoId);
        repository.delete(memoId);
        activity.touch(memo.projectId());
    }

    private void requireProject(UUID ownerUserId, UUID projectId) {
        if (!repository.projectExists(ownerUserId, projectId)) {
            throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
        }
    }

    private MemoDtos.Memo require(UUID ownerUserId, UUID memoId) {
        return repository.find(ownerUserId, memoId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.MEMO_NOT_FOUND));
    }

    private static String normalizeScope(String scope) {
        return scope == null ? "" : scope.trim().toUpperCase();
    }

    /** 빈 메모를 허용한다. 화면이 새 메모 카드를 먼저 만들고 사용자가 이어서 쓴다. */
    private static String validateBody(String raw) {
        String body = raw == null ? "" : raw;
        if (body.length() > BODY_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_MEMO);
        }
        return body;
    }

    private static String validateTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String title = raw.trim();
        if (title.length() > TITLE_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_MEMO);
        }
        return title.isEmpty() ? null : title;
    }
}
