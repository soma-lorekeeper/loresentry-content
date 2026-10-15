package com.loresentry.content.chat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.web.ContentFailure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ChatService {

    private final ChatRepository repository;

    ChatService(ChatRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    ChatResponses.Snapshot snapshot(UUID ownerUserId, UUID projectId) {
        ChatResponses.Project project = repository.project(ownerUserId, projectId)
                .orElseThrow(() -> new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND));
        Map<UUID, List<ChatResponses.Property>> properties = repository.properties(projectId);
        List<ChatResponses.Manuscript> manuscripts = repository.manuscripts(projectId, properties);
        List<ChatResponses.Setting> settings = repository.settings(projectId, properties);
        List<ChatResponses.Relation> relations = repository.relations(projectId);
        List<ChatResponses.Memo> memos = repository.memos(projectId);
        return new ChatResponses.Snapshot(project, manuscripts, settings, relations, memos,
                sizeOf(manuscripts, settings, relations, memos));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    ChatResponses.Size size(UUID ownerUserId, UUID projectId) {
        if (!repository.projectExists(ownerUserId, projectId)) {
            throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
        }
        return new ChatResponses.Size(repository.size(projectId));
    }

    private static long sizeOf(List<ChatResponses.Manuscript> manuscripts, List<ChatResponses.Setting> settings,
            List<ChatResponses.Relation> relations, List<ChatResponses.Memo> memos) {
        long size = 0;
        for (ChatResponses.Manuscript manuscript : manuscripts) {
            size += codePoints(manuscript.title()) + codePoints(manuscript.bodyText());
            for (ChatResponses.Property property : manuscript.properties()) {
                size += codePoints(property.value());
            }
        }
        for (ChatResponses.Setting setting : settings) {
            size += codePoints(setting.title()) + codePoints(setting.bodyText());
            for (ChatResponses.Property property : setting.properties()) {
                size += codePoints(property.value());
            }
        }
        for (ChatResponses.Relation relation : relations) {
            size += codePoints(relation.description());
        }
        for (ChatResponses.Memo memo : memos) {
            size += codePoints(memo.title()) + codePoints(memo.body());
        }
        return size;
    }

    private static long codePoints(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }
}
