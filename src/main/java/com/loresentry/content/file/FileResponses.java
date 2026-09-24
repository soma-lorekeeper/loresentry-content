package com.loresentry.content.file;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
            @JsonProperty("char_count") int charCount,
            @JsonProperty("revision_no") long revisionNo,
            @JsonProperty("trashed_at") OffsetDateTime trashedAt,
            @JsonProperty("updated_at") OffsetDateTime updatedAt) {

        public static Document from(FileRows.Document row) {
            return new Document(row.id(), row.title(), row.folderCode(), row.episodeId(), row.rank(),
                    row.locked(), row.charCount(), row.revisionNo(), row.trashedAt(), row.updatedAt());
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
            List<FileRows.Document> documents) {
        return new Tree(
                folders.stream().map(Folder::from).toList(),
                episodes.stream().map(Episode::from).toList(),
                documents.stream().map(Document::from).toList());
    }

    static TrashList trash(List<FileRows.TrashEntry> entries) {
        return new TrashList(entries.stream().map(TrashEntry::from).toList());
    }
}
