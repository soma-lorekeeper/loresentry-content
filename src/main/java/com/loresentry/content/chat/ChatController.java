package com.loresentry.content.chat;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.snapshot(userId, projectId));
    }

    @GetMapping("/internal/projects/{projectId}/chat-size")
    public ResponseEntity<ChatResponses.Size> size(@CurrentUser UUID userId, @PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(chat.size(userId, projectId));
    }
}
