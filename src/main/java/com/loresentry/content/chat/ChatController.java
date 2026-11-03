package com.loresentry.content.chat;

import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.web.CurrentUser;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatController {

    private final ChatService chat;

    ChatController(ChatService chat) {
        this.chat = chat;
    }

    @GetMapping("/internal/projects/{projectId}/chat-snapshot")
    public ResponseEntity<ChatResponses.Snapshot> snapshot(@CurrentUser UUID userId,
            @PathVariable UUID projectId) {
        AppLogger.current().info("chat snapshot get requested", LogField.projectId(projectId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.snapshot(userId, projectId));
    }

    @GetMapping("/internal/projects/{projectId}/chat-size")
    public ResponseEntity<ChatResponses.Size> size(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("chat size get requested", LogField.projectId(projectId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.size(userId, projectId));
    }

    @GetMapping("/internal/projects/{projectId}/chat-map")
    public ResponseEntity<ChatResponses.ChatMap> map(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        AppLogger.current().info("chat map get requested", LogField.projectId(projectId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.map(userId, projectId));
    }

    @PostMapping("/internal/projects/{projectId}/chat-documents")
    public ResponseEntity<ChatResponses.Documents> documents(@CurrentUser UUID userId,
            @PathVariable UUID projectId, @RequestBody ChatResponses.DocumentsRequest request) {
        // 요청한 문서·메모 ID는 목록이라 개수만 남는다
        AppLogger.current().info("chat documents get requested", LogField.projectId(projectId),
                LogField.param("document_ids", request == null ? null : request.documentIds()),
                LogField.param("memo_ids", request == null ? null : request.memoIds()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(chat.documents(userId, projectId, request));
    }
}
