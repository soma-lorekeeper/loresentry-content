package com.loresentry.content.refresh;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검토 작업본({@code refresh_document_drafts}). 작업본의 생애:
 *
 * <ol>
 *   <li>요청 때 S3 에 실은 문서마다 left 만 채워 만든다(BASE_CAPTURED).
 *   <li>결과가 오면 제안이 있는 작업본에 right 를 채우고(OPEN), 제안이 없는 것은 지운다. graph-rag 가 더 읽은 문서나
 *       새 설정 문서 제안은 이때 새로 만든다.
 *   <li>검토 중에는 사용자가 left·right 를 고친다. 두 쪽이 같아지면 matched 다.
 *   <li>run 이 끝나면(확정·변경 없음·실패·시간 초과) 모두 지운다.
 * </ol>
 */
@Repository
class RefreshDraftRepository {

    private final JdbcClient jdbcClient;

    private final JsonMapper json = JsonMapper.builder().build();

    RefreshDraftRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 요청 때 실은 문서의 작업본. left 와 그 원본(origin)은 실은 그 상태이고, 기준 버전이 같은 상태를 보관한다.
     * right 는 결과가 와야 생긴다.
     */
    void insertBaseDraft(UUID runId, UUID documentId, UUID baseVersionId, long revisionNo, DraftSnapshot left) {
        String leftJson = json.writeValueAsString(left);
        jdbcClient
                .sql("""
                        insert into refresh_document_drafts
                            (refresh_run_id, kind, target_document_id, base_version_id, left_source_revision_no,
                             left_snapshot, origin_snapshot, status)
                        values (:run, 'EDIT', :document, :base, :revision, :left::jsonb, :left::jsonb,
                                'BASE_CAPTURED')
                        """)
                .param("run", runId)
                .param("document", documentId)
                .param("base", baseVersionId)
                .param("revision", revisionNo)
                .param("left", leftJson)
                .update();
    }
}
