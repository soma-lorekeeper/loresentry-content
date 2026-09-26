package com.loresentry.content.memo;

import java.util.UUID;

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
        return memos.list(userId, projectId, scope, documentId);
    }

    @PostMapping("/projects/{projectId}/memos")
    public ResponseEntity<MemoDtos.Memo> create(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestBody MemoDtos.CreateRequest request) {
        MemoDtos.Memo memo = memos.create(userId, projectId, request);
        return ResponseEntity.created(java.net.URI.create("/memos/" + memo.id())).body(memo);
    }

    @PatchMapping("/memos/{memoId}")
    public MemoDtos.Memo update(
            @CurrentUser UUID userId,
            @PathVariable UUID memoId,
            @RequestBody MemoDtos.UpdateRequest request) {
        return memos.update(userId, memoId, request);
    }

    @DeleteMapping("/memos/{memoId}")
    public ResponseEntity<Void> delete(@CurrentUser UUID userId, @PathVariable UUID memoId) {
        memos.delete(userId, memoId);
        return ResponseEntity.noContent().build();
    }
}
