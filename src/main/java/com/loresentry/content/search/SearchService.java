package com.loresentry.content.search;

import java.util.UUID;

import com.loresentry.content.web.ContentFailure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SearchService {

    static final int LIMIT = 50;

    /** 조각 앞뒤로 보여 줄 글자 수. */
    private static final int CONTEXT = 40;

    private final SearchRepository repository;

    private final JdbcClient jdbcClient;

    public SearchService(SearchRepository repository, JdbcClient jdbcClient) {
        this.repository = repository;
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public SearchResponses.Results search(UUID ownerUserId, UUID projectId, String rawQuery) {
        if (!projectExists(ownerUserId, projectId)) {
            throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
        }

        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            // 빈 검색어는 오류가 아니라 결과 없음이다. 화면에 빈 상태가 따로 있다.
            return new SearchResponses.Results(java.util.List.of());
        }

        return new SearchResponses.Results(
                repository.search(ownerUserId, projectId, query, LIMIT).stream()
                        .map(row -> new SearchResponses.Hit(row.id(), row.title(), row.folderCode(),
                                row.episodeName(), snippetOf(row.bodyMd(), query), row.updatedAt()))
                        .toList());
    }

    private boolean projectExists(UUID ownerUserId, UUID projectId) {
        return jdbcClient
                .sql("select 1 from projects where id = :id and owner_user_id = :owner"
                        + " and trashed_at is null")
                .param("id", projectId).param("owner", ownerUserId)
                .query(Integer.class).optional().isPresent();
    }

    /** 제목만 일치한 문서는 조각이 없다. 화면이 제목을 이미 굵게 보여 주므로 억지로 만들지 않는다. */
    private static SearchResponses.Snippet snippetOf(String body, String query) {
        if (body == null) {
            return null;
        }
        int at = body.toLowerCase().indexOf(query.toLowerCase());
        if (at < 0) {
            return null;
        }

        int start = Math.max(0, at - CONTEXT);
        int end = Math.min(body.length(), at + query.length() + CONTEXT);
        return new SearchResponses.Snippet(
                body.substring(start, at),
                body.substring(at, at + query.length()),
                body.substring(at + query.length(), end));
    }
}
