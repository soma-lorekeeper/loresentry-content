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
            boolean locked,
            int charCount,
            long revisionNo,
            OffsetDateTime trashedAt,
            OffsetDateTime updatedAt) {

        public boolean isTrashed() {
            return trashedAt != null;
        }
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
