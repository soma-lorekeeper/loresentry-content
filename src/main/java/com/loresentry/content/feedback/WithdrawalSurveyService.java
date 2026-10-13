package com.loresentry.content.feedback;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.loresentry.content.web.ContentFailure;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WithdrawalSurveyService {

    static final int COMMENT_MAX = 1000;

    static final Set<String> REASONS = Set.of(
            "RARELY_USED", "MISSING_FEATURE", "HARD_TO_USE", "UNRELIABLE",
            "SWITCHED_SERVICE", "PRIVACY", "PAUSED_WRITING", "OTHER");

    private final WithdrawalSurveyRepository repository;

    public WithdrawalSurveyService(WithdrawalSurveyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(List<String> rawReasons, String rawComment) {
        Set<String> reasons = new LinkedHashSet<>();
        for (String raw : rawReasons == null ? List.<String>of() : rawReasons) {
            String reason = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (!REASONS.contains(reason)) {
                throw new ContentFailure(ContentFailure.Reason.INVALID_WITHDRAWAL_SURVEY);
            }
            reasons.add(reason);
        }
        String comment = rawComment == null ? "" : rawComment.trim();
        if (comment.codePointCount(0, comment.length()) > COMMENT_MAX
                || (reasons.isEmpty() && comment.isEmpty())) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_WITHDRAWAL_SURVEY);
        }
        repository.insert(reasons.toArray(String[]::new), comment.isEmpty() ? null : comment);
    }
}
