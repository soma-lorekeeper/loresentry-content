package com.loresentry.content.memo;

import java.util.Set;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.project.ProjectActivity;
import com.loresentry.content.web.ContentFailure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoService {

    static final int BODY_MAX = 20_000;

    static final int TITLE_MAX = 200;

    /** 메모의 범위(scope) 값. 로그의 scope 는 이 목록 안이면 그대로, 밖이면 other 로 남는다. */
    static final Set<String> SCOPES = Set.of("PROJECT", "FILE");

    private final MemoRepository repository;

    private final ProjectActivity activity;

    public MemoService(MemoRepository repository, ProjectActivity activity) {
        this.repository = repository;
        this.activity = activity;
    }

    @Transactional(readOnly = true)
    public MemoDtos.MemoList list(UUID ownerUserId, UUID projectId, String scope, UUID documentId) {
        // scope 는 대문자로 맞춘 값이 PROJECT·FILE 이면 그대로, 아니면 other 로 남는다
        AppLogger.current().info("memo list", LogField.projectId(projectId), LogField.documentId(documentId),
                LogField.code("scope", normalizeScope(scope), SCOPES));
        requireProject(ownerUserId, projectId);

        return new MemoDtos.MemoList(switch (normalizeScope(scope)) {
            case "PROJECT" -> repository.listProjectMemos(projectId);
            // document_id 가 없으면 프로젝트의 파일 메모 전부다. 메모 전용 화면이 그렇게 본다.
            case "FILE" -> documentId == null
                    ? repository.listAllFileMemos(projectId)
                    : repository.listFileMemos(projectId, documentId);
            default -> throw new ContentFailure(ContentFailure.Reason.INVALID_MEMO);
        });
    }

    @Transactional
    public MemoDtos.Memo create(UUID ownerUserId, UUID projectId, MemoDtos.CreateRequest request) {
        // 메모가 붙을 문서는 메모의 범위라 전용 필드(document_id)다. 제목·본문은 길이만 남는다
        AppLogger.current().info("memo create", LogField.projectId(projectId),
                LogField.documentId(request == null ? null : request.documentId()),
                LogField.code("scope", request == null ? null : normalizeScope(request.scope()), SCOPES),
                LogField.param("title", request == null ? null : request.title()),
                LogField.param("body", request == null ? null : request.body()));
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
        // 새로 만든 메모
        AppLogger.current().info("memo create completed", LogField.projectId(projectId), LogField.memoId(memo.id()));
        return memo;
    }

    /**
     * 본문 전체를 덮어쓴다. 버전 충돌을 검사하지 않는다 — 메모는 한 사람이 한 칸에 쓰는 짧은 글이고,
     * 문서 본문처럼 병합할 구조가 없다. 마지막 저장이 이긴다(프론트 가정과 같다).
     */
    @Transactional
    public MemoDtos.Memo update(UUID ownerUserId, UUID memoId, MemoDtos.UpdateRequest request) {
        AppLogger.current().info("memo update", LogField.memoId(memoId),
                LogField.param("title", request == null ? null : request.title()),
                LogField.param("body", request == null ? null : request.body()));
        MemoDtos.Memo memo = require(ownerUserId, memoId);
        String title = request.title() == null ? memo.title() : validateTitle(request.title());
        String body = request.body() == null ? memo.body() : validateBody(request.body());

        MemoDtos.Memo updated = repository.update(memoId, title, body);
        activity.touch(memo.projectId());
        return updated;
    }

    @Transactional
    public void delete(UUID ownerUserId, UUID memoId) {
        AppLogger.current().info("memo delete", LogField.memoId(memoId));
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
