package com.loresentry.content.file;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class FileResponses {

    private FileResponses() {
    }

    /**
     * 서버는 정규화된 세 목록을 준다. <b>트리 모양은 화면이 조립한다.</b>
     *
     * <p>기본 분류 폴더는 전역 시드이고 에피소드는 프로젝트별 행이라, 서버가 트리를 만들려면
     * 실제 행이 없는 분류 폴더에 가짜 id를 발급해야 한다. 그 id는 DB에 없는 값이므로 클라이언트가
     * 그걸 들고 다시 요청하면 무엇도 가리키지 않는다. 대신 실제 모델을 그대로 주고, 어떤 노드를
     * 어떻게 겹쳐 보일지는 화면이 정한다.
     */
    public record Tree(
            List<Folder> folders,
            List<Episode> episodes,
            List<Document> documents) {
    }

    public record Folder(String code, String name, int position) {

        static Folder from(FileRows.BaseFolder row) {
            return new Folder(row.code(), row.name(), row.position());
        }
    }

    public record Episode(UUID id, String name, String rank) {

        static Episode from(FileRows.Episode row) {
            return new Episode(row.id(), row.name(), row.rank());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Document(
            UUID id,
            String title,
            @JsonProperty("folder_code") String folderCode,
            @JsonProperty("episode_id") UUID episodeId,
            String rank,
            boolean locked,
            /** 그래프 최신화가 잠갔다. 화면은 이것으로 "최신화 중이라 잠김"을 알리고 잠금 해제를 막는다 */
            @JsonProperty("refresh_locked") boolean refreshLocked,
            @JsonProperty("char_count") int charCount,
            @JsonProperty("revision_no") long revisionNo,
            /** 대표 이미지 주소. 없으면 null */
            @JsonProperty("image_url") String imageUrl,
            @JsonProperty("trashed_at") OffsetDateTime trashedAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {

        /** @param publicUrl S3 키를 공개 주소로 바꾼다. 주소의 앞부분은 설정이라 행에 두지 않는다 */
        public static Document from(FileRows.Document row, UnaryOperator<String> publicUrl) {
            return new Document(row.id(), row.title(), row.folderCode(), row.episodeId(), row.rank(),
                    row.locked(), row.refreshLocked(), row.charCount(), row.revisionNo(),
                    row.imageKey() == null ? null : publicUrl.apply(row.imageKey()),
                    row.trashedAt(), row.updatedAt());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TrashEntry(
            UUID id,
            String title,
            @JsonProperty("folder_code") String folderCode,
            @JsonProperty("episode_name") String episodeName,
            @JsonProperty("trashed_at") OffsetDateTime trashedAt) {

        static TrashEntry from(FileRows.TrashEntry row) {
            return new TrashEntry(row.id(), row.title(), row.folderCode(),
                    row.episodeName(), row.trashedAt());
        }
    }

    public record TrashList(List<TrashEntry> files) {
    }

    static Tree tree(List<FileRows.BaseFolder> folders, List<FileRows.Episode> episodes,
            List<FileRows.Document> documents, UnaryOperator<String> publicUrl) {
        return new Tree(
                folders.stream().map(Folder::from).toList(),
                episodes.stream().map(Episode::from).toList(),
                documents.stream().map(document -> Document.from(document, publicUrl)).toList());
    }

    static TrashList trash(List<FileRows.TrashEntry> entries) {
        return new TrashList(entries.stream().map(TrashEntry::from).toList());
    }
}
