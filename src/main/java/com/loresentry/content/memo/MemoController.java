package com.loresentry.content.memo;

import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.web.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemoController {

    private final MemoService memos;

    public MemoController(MemoService memos) {
        this.memos = memos;
    }

    @GetMapping("/projects/{projectId}/memos")
    public MemoDtos.MemoList list(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestParam(name = "scope") String scope,
            @RequestParam(name = "document_id", required = false) UUID documentId) {
        // 메모가 붙은 문서는 메모의 범위라 전용 필드(document_id)다. scope 는 허용 목록을 가진 service 줄에 남는다
        AppLogger.current().info("memo list requested", LogField.projectId(projectId), LogField.documentId(documentId));
        return memos.list(userId, projectId, scope, documentId);
    }

    @PostMapping("/projects/{projectId}/memos")
    public ResponseEntity<MemoDtos.Memo> create(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestBody MemoDtos.CreateRequest request) {
        // 제목·본문은 가려져 길이만 남는다
        AppLogger.current().info("memo create requested", LogField.projectId(projectId),
                LogField.documentId(request == null ? null : request.documentId()),
                LogField.param("title", request == null ? null : request.title()),
                LogField.param("body", request == null ? null : request.body()));
        MemoDtos.Memo memo = memos.create(userId, projectId, request);
        return ResponseEntity.created(java.net.URI.create("/memos/" + memo.id())).body(memo);
    }

    @PatchMapping("/memos/{memoId}")
    public MemoDtos.Memo update(
            @CurrentUser UUID userId,
            @PathVariable UUID memoId,
            @RequestBody MemoDtos.UpdateRequest request) {
        AppLogger.current().info("memo update requested", LogField.memoId(memoId),
                LogField.param("title", request == null ? null : request.title()),
                LogField.param("body", request == null ? null : request.body()));
        return memos.update(userId, memoId, request);
    }

    @DeleteMapping("/memos/{memoId}")
    public ResponseEntity<Void> delete(@CurrentUser UUID userId, @PathVariable UUID memoId) {
        AppLogger.current().info("memo delete requested", LogField.memoId(memoId));
        memos.delete(userId, memoId);
        return ResponseEntity.noContent().build();
    }
}
