package com.loresentry.content.file;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 파일 트리를 만드는 데 필요한 행들. 트리 모양은 서버가 아니라 화면이 조립한다. */
public final class FileRows {

    private FileRows() {
    }

    public record BaseFolder(short id, String code, String name, int position) {
    }

    public record Episode(UUID id, String name, String rank) {
    }

    public record Document(
            UUID id,
            UUID projectId,
            short folderId,
            String folderCode,
            UUID episodeId,
            String title,
            String rank,
            /** 편집할 수 없는가. 사용자 잠금이나 최신화 잠금 중 하나라도 있으면 true */
            boolean locked,
            /** 진행 중인 그래프 최신화가 잠갔는가. 사용자가 풀 수 없다 */
            boolean refreshLocked,
            int charCount,
            long revisionNo,
            OffsetDateTime trashedAt,
            OffsetDateTime updatedAt) {

        public boolean isTrashed() {
            return trashedAt != null;
        }
    }

    /**
     * 이동·복원 뒤의 문서와, 그 update 가 분류나 에피소드를 실제로 바꿨는지. 순서(rank)만 바뀐 이동은 false 다.
     * 바뀌었는지는 update 문이 돌려준 전후 값으로 정한다 — 미리 읽은 위치와 비교하면 그 사이의 다른 이동에 속는다.
     */
    public record Placed(Document document, boolean locationChanged) {
    }

    /** 휴지통 항목. 원래 위치를 보여 주려면 분류와 에피소드 이름이 함께 있어야 한다. */
    public record TrashEntry(
            UUID id,
            String title,
            String folderCode,
            String episodeName,
            OffsetDateTime trashedAt) {
    }
}
