package com.loresentry.content.project;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code projects} 한 행. 도메인 표현이고 JSON 모양은 {@link ProjectResponse}가 정한다.
 */
public record Project(
        UUID id,
        UUID ownerUserId,
        String name,
        String description,
        OffsetDateTime trashedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        LastFile lastFile) {

    /**
     * 프로젝트 카드가 "마지막으로 작업한 파일"을 보여 준다(요구사항 §2.2). 별도 열람 기록 테이블을
     * 두지 않고 가장 최근에 수정된 활성 문서로 본다 — 사용자가 인식하는 "작업"은 편집이다.
     */
    public record LastFile(UUID id, String title) {
    }

    public boolean isTrashed() {
        return trashedAt != null;
    }
}
