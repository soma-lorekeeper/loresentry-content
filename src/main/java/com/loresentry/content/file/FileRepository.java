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

    /** 빈 본문. 문단 하나를 가진 문서다(에디터가 빈 문서를 그렇게 만든다). */
    private static final String EMPTY_BODY =
            "{\"schema_version\":1,\"doc\":{\"type\":\"doc\","
                    + "\"content\":[{\"type\":\"paragraph\"}]}}";

    /**
     * 문서 조회는 언제나 {@code projects}와 조인해 소유자를 확인한다. 서비스 계층에서 두 번
     * 질의해 비교하면 그 사이에 소유자가 바뀔 수 있고, 무엇보다 한 곳만 빠뜨려도 남의 문서가 보인다.
     */
    // locked 는 편집할 수 있는지다. 사용자 잠금과 최신화 잠금(refresh_lock_run_id) 중 하나라도 걸려 있으면 잠김이다.
    // 둘을 따로 두는 이유는 V16 주석에 있다. refresh_locked 는 화면이 "최신화 때문에 잠김"을 알리는 데 쓴다.
    private static final String DOCUMENT_SELECT = """
            select d.id, d.project_id, d.folder_id, f.code as folder_code, d.episode_id,
                   d.title, d.rank,
                   (d.locked or d.refresh_lock_run_id is not null) as locked,
                   d.refresh_lock_run_id is not null as refresh_locked,
                   d.char_count, d.revision_no, i.s3_key as image_key, d.trashed_at, d.updated_at
            from document d
            join base_folders f on f.id = d.folder_id
            join projects p on p.id = d.project_id
            left join image i on i.id = d.image_id
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
                        insert into document
                            (project_id, folder_id, episode_id, title, rank, body_json)
                        values (:project, :folder, :episode, :title, :rank, :body::jsonb)
                        returning id
                        """)
                .param("project", projectId)
                .param("folder", folderId)
                .param("episode", episodeId)
                .param("title", title)
                .param("rank", rank)
                // 새 문서는 처음부터 JSON 본문을 갖는다. 비워 두면 변환 전 레거시 행처럼 보인다.
                .param("body", EMPTY_BODY)
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

    FileRows.Placed moveDocument(UUID fileId, short folderId, UUID episodeId, String rank) {
        boolean locationChanged = jdbcClient
                .sql("""
                        update document
                        set folder_id = :folder, episode_id = :episode, rank = :rank, updated_at = now()
                        where id = :id
                        returning old.folder_id is distinct from new.folder_id
                               or old.episode_id is distinct from new.episode_id
                        """)
                .param("id", fileId).param("folder", folderId)
                .param("episode", episodeId).param("rank", rank)
                .query(Boolean.class)
                .single();
        return new FileRows.Placed(requireDocument(fileId), locationChanged);
    }

    /** @return 이 호출이 휴지통으로 보냈나. 이미 휴지통에 있었으면 false 다. */
    boolean trashDocument(UUID fileId) {
        return jdbcClient.sql("update document set trashed_at = now(), updated_at = now()"
                        + " where id = :id and trashed_at is null")
                .param("id", fileId).update() == 1;
    }

    /** 원래 있던 에피소드가 사라졌으면 원고 폴더 최상위로 돌린다(요구사항 §4.2). */
    FileRows.Placed restoreDocument(UUID fileId, boolean dropEpisode, String rank) {
        boolean locationChanged = jdbcClient
                .sql("""
                        update document
                        set trashed_at = null,
                            episode_id = case when :dropEpisode then null else episode_id end,
                            rank = :rank,
                            updated_at = now()
                        where id = :id
                        returning old.episode_id is distinct from new.episode_id
                        """)
                .param("id", fileId).param("dropEpisode", dropEpisode).param("rank", rank)
                .query(Boolean.class)
                .single();
        return new FileRows.Placed(requireDocument(fileId), locationChanged);
    }

    /**
     * 영구 삭제 전에 문서 행을 잠근다. 이 lock 은 다른 문서가 이 문서로 새 관계를 거는 것(외래 키 확인)을 막는다.
     * 잠그지 않으면 이웃을 읽은 뒤 생긴 관계가 삭제에 휩쓸려 사라지는데, 그 관계를 가진 문서의 이벤트는 나가지 않는다.
     */
    void lockForDelete(UUID fileId) {
        jdbcClient.sql("select id from document where id = :id for update")
                .param("id", fileId).query(UUID.class).optional();
    }

    /** 이 문서가 high 인 관계의 low 문서들. 그 관계는 low 문서가 가지므로, 이 문서가 사라지면 그 문서들이 바뀐다. */
    List<UUID> relationOwnersOf(UUID fileId) {
        return jdbcClient
                .sql("select low_document_id from document_relations where high_document_id = :id"
                        + " order by low_document_id")
                .param("id", fileId)
                .query(UUID.class)
                .list();
    }

    void deleteDocument(UUID fileId) {
        jdbcClient.sql("delete from document where id = :id").param("id", fileId).update();
    }

    /** 이미지가 이 프로젝트의 것이고 업로드가 확인됐는가. PENDING 이면 아직 주소를 줄 수 없다. */
    Optional<String> imageStatus(UUID projectId, UUID imageId) {
        return jdbcClient.sql("select status from image where id = :id and project_id = :project")
                .param("id", imageId).param("project", projectId)
                .query(String.class)
                .optional();
    }

    /**
     * 문서의 이미지를 바꾼다.
     *
     * @return 이전 이미지 id. 없었거나 같은 이미지면 비어 있다
     */
    Optional<UUID> replaceImage(UUID fileId, UUID imageId) {
        UUID previous = jdbcClient
                .sql("update document set image_id = :image where id = :id returning old.image_id")
                .param("id", fileId).param("image", imageId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        return previous == null || previous.equals(imageId) ? Optional.empty() : Optional.of(previous);
    }

    /** 이미지 행과 그 S3 키. 행을 지운 뒤에는 어느 객체를 지울지 다시 찾을 수 없으므로 키를 먼저 돌려준다. */
    Optional<String> deleteImage(UUID imageId) {
        return jdbcClient.sql("delete from image where id = :id returning s3_key")
                .param("id", imageId)
                .query(String.class)
                .optional();
    }

    Optional<UUID> imageOf(UUID fileId) {
        return jdbcClient.sql("select image_id from document where id = :id and image_id is not null")
                .param("id", fileId)
                .query(UUID.class)
                .optional();
    }

    /**
     * 에피소드만 지운다. 안의 회차는 원고 폴더로 돌아간다(와이어프레임 166).
     *
     * @return 원고 폴더로 돌아간 문서들(휴지통 문서 포함). update 문이 실제로 바꾼 행이다
     */
    List<UUID> deleteEpisode(UUID episodeId) {
        List<UUID> moved = jdbcClient
                .sql("update document set episode_id = null, updated_at = now() where episode_id = :id"
                        + " returning id")
                .param("id", episodeId).query(UUID.class).list();
        jdbcClient.sql("delete from episode_folders where id = :id").param("id", episodeId).update();
        return moved;
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
                rows.getBoolean("refresh_locked"),
                rows.getInt("char_count"),
                rows.getLong("revision_no"),
                rows.getString("image_key"),
                rows.getObject("trashed_at", OffsetDateTime.class),
                rows.getObject("updated_at", OffsetDateTime.class));
    }
}
