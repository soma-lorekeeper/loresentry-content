package com.loresentry.content.file;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FileRepository {

    /**
     * 문서 조회는 언제나 {@code projects}와 조인해 소유자를 확인한다. 서비스 계층에서 두 번
     * 질의해 비교하면 그 사이에 소유자가 바뀔 수 있고, 무엇보다 한 곳만 빠뜨려도 남의 문서가 보인다.
     */
    private static final String DOCUMENT_SELECT = """
            select d.id, d.project_id, d.folder_id, f.code as folder_code, d.episode_id,
                   d.title, d.rank, d.locked, d.char_count, d.revision_no, d.trashed_at, d.updated_at
            from document d
            join base_folders f on f.id = d.folder_id
            join projects p on p.id = d.project_id
            """;

    private final JdbcClient jdbcClient;

    public FileRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    List<FileRows.BaseFolder> baseFolders() {
        return jdbcClient.sql("select id, code, name, position from base_folders order by position")
                .query((rows, index) -> new FileRows.BaseFolder(
                        rows.getShort("id"), rows.getString("code"),
                        rows.getString("name"), rows.getInt("position")))
                .list();
    }

    boolean projectExists(UUID ownerUserId, UUID projectId) {
        return jdbcClient
                .sql("select 1 from projects where id = :id and owner_user_id = :owner and trashed_at is null")
                .param("id", projectId)
                .param("owner", ownerUserId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    List<FileRows.Episode> episodes(UUID projectId) {
        return jdbcClient
                .sql("select id, name, rank from episode_folders where project_id = :project order by rank")
                .param("project", projectId)
                .query((rows, index) -> new FileRows.Episode(
                        rows.getObject("id", UUID.class), rows.getString("name"), rows.getString("rank")))
                .list();
    }

    List<FileRows.Document> activeDocuments(UUID projectId) {
        return jdbcClient
                .sql(DOCUMENT_SELECT + " where d.project_id = :project and d.trashed_at is null"
                        + " order by d.folder_id, d.rank")
                .param("project", projectId)
                .query(FileRepository::mapDocument)
                .list();
    }

    List<FileRows.TrashEntry> trashedDocuments(UUID projectId) {
        return jdbcClient
                .sql("""
                        select d.id, d.title, f.code as folder_code, e.name as episode_name, d.trashed_at
                        from document d
                        join base_folders f on f.id = d.folder_id
                        left join episode_folders e on e.id = d.episode_id
                        where d.project_id = :project and d.trashed_at is not null
                        order by d.trashed_at desc
                        """)
                .param("project", projectId)
                .query((rows, index) -> new FileRows.TrashEntry(
                        rows.getObject("id", UUID.class), rows.getString("title"),
                        rows.getString("folder_code"), rows.getString("episode_name"),
                        rows.getObject("trashed_at", OffsetDateTime.class)))
                .list();
    }

    Optional<FileRows.Document> findDocument(UUID ownerUserId, UUID fileId) {
        return jdbcClient
                .sql(DOCUMENT_SELECT + " where d.id = :id and p.owner_user_id = :owner")
                .param("id", fileId)
                .param("owner", ownerUserId)
                .query(FileRepository::mapDocument)
                .optional();
    }

    Optional<FileRows.Episode> findEpisode(UUID ownerUserId, UUID episodeId) {
        return jdbcClient
                .sql("""
                        select e.id, e.name, e.rank
                        from episode_folders e
                        join projects p on p.id = e.project_id
                        where e.id = :id and p.owner_user_id = :owner
                        """)
                .param("id", episodeId)
                .param("owner", ownerUserId)
                .query((rows, index) -> new FileRows.Episode(
                        rows.getObject("id", UUID.class), rows.getString("name"), rows.getString("rank")))
                .optional();
    }

    UUID projectOfEpisodeOrNull(UUID episodeId) {
        return jdbcClient.sql("select project_id from episode_folders where id = :id")
                .param("id", episodeId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    /** 같은 위치의 활성 형제 순서 값을 오름차순으로. rank 계산에만 쓴다. */
    List<String> siblingRanks(UUID projectId, short folderId, UUID episodeId, UUID excludingFileId) {
        return jdbcClient
                .sql("""
                        select rank from document
                        where project_id = :project
                          and folder_id = :folder
                          and episode_id is not distinct from :episode
                          and trashed_at is null
                          and (:excluding::uuid is null or id <> :excluding::uuid)
                        order by rank
                        """)
                .param("project", projectId)
                .param("folder", folderId)
                .param("episode", episodeId)
                .param("excluding", excludingFileId)
                .query(String.class)
                .list();
    }

    List<String> episodeRanks(UUID projectId) {
        return jdbcClient
                .sql("select rank from episode_folders where project_id = :project order by rank")
                .param("project", projectId)
                .query(String.class)
                .list();
    }

    Optional<String> rankOfSibling(UUID projectId, short folderId, UUID episodeId, UUID fileId) {
        return jdbcClient
                .sql("""
                        select rank from document
                        where id = :id and project_id = :project and folder_id = :folder
                          and episode_id is not distinct from :episode and trashed_at is null
                        """)
                .param("id", fileId)
                .param("project", projectId)
                .param("folder", folderId)
                .param("episode", episodeId)
                .query(String.class)
                .optional();
    }

    FileRows.Document insertDocument(UUID projectId, short folderId, UUID episodeId, String title, String rank) {
        UUID id = jdbcClient
                .sql("""
                        insert into document (project_id, folder_id, episode_id, title, rank)
                        values (:project, :folder, :episode, :title, :rank)
                        returning id
                        """)
                .param("project", projectId)
                .param("folder", folderId)
                .param("episode", episodeId)
                .param("title", title)
                .param("rank", rank)
                .query(UUID.class)
                .single();
        return requireDocument(id);
    }

    FileRows.Episode insertEpisode(UUID projectId, UUID createdBy, String name, String rank) {
        return jdbcClient
                .sql("""
                        insert into episode_folders (project_id, created_by_user_id, name, rank)
                        values (:project, :user, :name, :rank)
                        returning id, name, rank
                        """)
                .param("project", projectId)
                .param("user", createdBy)
                .param("name", name)
                .param("rank", rank)
                .query((rows, index) -> new FileRows.Episode(
                        rows.getObject("id", UUID.class), rows.getString("name"), rows.getString("rank")))
                .single();
    }

    FileRows.Document renameDocument(UUID fileId, String title) {
        jdbcClient.sql("update document set title = :title, updated_at = now() where id = :id")
                .param("id", fileId).param("title", title).update();
        return requireDocument(fileId);
    }

    FileRows.Episode renameEpisode(UUID episodeId, String name) {
        return jdbcClient
                .sql("update episode_folders set name = :name, updated_at = now() where id = :id"
                        + " returning id, name, rank")
                .param("id", episodeId).param("name", name)
                .query((rows, index) -> new FileRows.Episode(
                        rows.getObject("id", UUID.class), rows.getString("name"), rows.getString("rank")))
                .single();
    }

    FileRows.Document moveDocument(UUID fileId, short folderId, UUID episodeId, String rank) {
        jdbcClient
                .sql("""
                        update document
                        set folder_id = :folder, episode_id = :episode, rank = :rank, updated_at = now()
                        where id = :id
                        """)
                .param("id", fileId).param("folder", folderId)
                .param("episode", episodeId).param("rank", rank)
                .update();
        return requireDocument(fileId);
    }

    void trashDocument(UUID fileId) {
        jdbcClient.sql("update document set trashed_at = now(), updated_at = now()"
                        + " where id = :id and trashed_at is null")
                .param("id", fileId).update();
    }

    /** 원래 있던 에피소드가 사라졌으면 원고 폴더 최상위로 돌린다(요구사항 §4.2). */
    FileRows.Document restoreDocument(UUID fileId, boolean dropEpisode, String rank) {
        jdbcClient
                .sql("""
                        update document
                        set trashed_at = null,
                            episode_id = case when :dropEpisode then null else episode_id end,
                            rank = :rank,
                            updated_at = now()
                        where id = :id
                        """)
                .param("id", fileId).param("dropEpisode", dropEpisode).param("rank", rank)
                .update();
        return requireDocument(fileId);
    }

    void deleteDocument(UUID fileId) {
        jdbcClient.sql("delete from document where id = :id").param("id", fileId).update();
    }

    /** 에피소드만 지운다. 안의 회차는 원고 폴더로 돌아간다(와이어프레임 166). */
    void deleteEpisode(UUID episodeId) {
        jdbcClient.sql("update document set episode_id = null, updated_at = now() where episode_id = :id")
                .param("id", episodeId).update();
        jdbcClient.sql("delete from episode_folders where id = :id").param("id", episodeId).update();
    }

    boolean episodeStillExists(UUID episodeId) {
        return episodeId != null && jdbcClient
                .sql("select 1 from episode_folders where id = :id")
                .param("id", episodeId).query(Integer.class).optional().isPresent();
    }

    private FileRows.Document requireDocument(UUID fileId) {
        return jdbcClient.sql(DOCUMENT_SELECT + " where d.id = :id")
                .param("id", fileId)
                .query(FileRepository::mapDocument)
                .single();
    }

    private static FileRows.Document mapDocument(ResultSet rows, int rowNum) throws SQLException {
        return new FileRows.Document(
                rows.getObject("id", UUID.class),
                rows.getObject("project_id", UUID.class),
                rows.getShort("folder_id"),
                rows.getString("folder_code"),
                rows.getObject("episode_id", UUID.class),
                rows.getString("title"),
                rows.getString("rank"),
                rows.getBoolean("locked"),
                rows.getInt("char_count"),
                rows.getLong("revision_no"),
                rows.getObject("trashed_at", OffsetDateTime.class),
                rows.getObject("updated_at", OffsetDateTime.class));
    }
}
