package com.loresentry.content.refresh;

import java.util.UUID;

import com.loresentry.content.document.RefreshDocuments;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * run 이 끝날 때(확정·변경 없음·실패·시간 초과) 남은 것을 치운다. 끝나는 길이 여럿이라 한 곳에 둔다.
 *
 * <ol>
 *   <li>작업본을 지운다. 검토가 끝났거나 할 수 없으므로 더 쓸 데가 없다.
 *   <li>그 작업본들의 기준 버전(REFRESH_BASE)을 지운다. 작업본이 기준 버전을 참조하므로 작업본이 먼저다.
 *   <li>이 run 이 건 문서 잠금을 푼다. 사용자 잠금은 그대로 남는다.
 * </ol>
 *
 * <p>결과 원본({@code refresh_results})과 확정이 남긴 AI_APPLY 버전은 남긴다.
 */
@Component
class RefreshCleanup {

    private final RefreshDraftRepository drafts;

    private final RefreshDocuments documents;

    RefreshCleanup(RefreshDraftRepository drafts, RefreshDocuments documents) {
        this.drafts = drafts;
        this.documents = documents;
    }

    /** 부르는 쪽이 run 상태를 바꾸는 트랜잭션 안에서 부른다. 상태 변경과 정리는 함께 확정되거나 함께 되돌려진다. */
    @Transactional(propagation = Propagation.MANDATORY)
    void release(UUID runId) {
        documents.deleteRefreshBases(drafts.deleteByRun(runId));
        documents.unlock(runId);
    }
}
