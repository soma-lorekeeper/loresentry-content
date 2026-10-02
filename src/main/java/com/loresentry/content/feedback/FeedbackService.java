package com.loresentry.content.feedback;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.loresentry.content.web.ContentFailure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeedbackService {

    static final int MESSAGE_MAX = 2000;

    static final int PAGE_MAX = 200;

    static final int CLIENT_MAX = 300;

    static final int HOURLY_LIMIT = 20;

    private static final Set<String> CATEGORIES = Set.of("BUG", "IDEA", "OTHER");

    private final FeedbackRepository repository;

    public FeedbackService(FeedbackRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public FeedbackDtos.Created create(UUID userId, FeedbackDtos.CreateRequest request) {
        String category = request.category() == null ? "" : request.category().trim().toUpperCase();
        if (!CATEGORIES.contains(category)) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_FEEDBACK);
        }
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty() || message.codePointCount(0, message.length()) > MESSAGE_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_FEEDBACK);
        }

        if (repository.countSince(userId, OffsetDateTime.now().minusHours(1)) >= HOURLY_LIMIT) {
            throw new ContentFailure(ContentFailure.Reason.FEEDBACK_RATE_LIMITED);
        }
        return repository.insert(userId, category, message,
                truncate(request.page(), PAGE_MAX), truncate(request.client(), CLIENT_MAX));
    }

    private static String truncate(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.codePointCount(0, value.length()) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }
}
