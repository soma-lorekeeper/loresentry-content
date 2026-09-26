package com.loresentry.content.memo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MemoRepository {

    private static final String COLUMNS =
            "id, project_id, scope, document_id, title, body, created_at, updated_at";

    private final JdbcClient jdbcClient;

    public MemoRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    boolean projectExists(UUID ownerUserId, UUID projectId) {
        return jdbcClient
                .sql("select 1 from projects where id = :id and owner_user_id = :owner"
                        + " and trashed_at is null")
                .param("id", projectId).param("owner", ownerUserId)
                .query(Integer.class).optional().isPresent();
    }

    /** 문서가 그 프로젝트의 활성 문서인지. 휴지통 문서에 메모를 새로 달 수는 없다. */
    boolean activeDocumentInProject(UUID projectId, UUID documentId) {
        return jdbcClient
                .sql("select 1 from document where id = :id and project_id = :project"
                        + " and trashed_at is null")
                .param("id", documentId).param("project", projectId)
                .query(Integer.class).optional().isPresent();
    }

    /** 최근 수정 순. 화면의 메모 카드 목록이 그 순서를 기대한다. */
    List<MemoDtos.Memo> listProjectMemos(UUID projectId) {
        return jdbcClient
                .sql("select " + COLUMNS + " from memo where project_id = :project"
                        + " and scope = 'PROJECT' order by updated_at desc, id desc")
                .param("project", projectId)
                .query(MemoRepository::mapRow)
                .list();
    }

    List<MemoDtos.Memo> listFileMemos(UUID projectId, UUID documentId) {
        return jdbcClient
                .sql("select " + COLUMNS + " from memo where project_id = :project"
                        + " and scope = 'FILE' and document_id = :document"
                        + " order by updated_at desc, id desc")
                .param("project", projectId).param("document", documentId)
                .query(MemoRepository::mapRow)
                .list();
    }

    MemoDtos.Memo insert(UUID projectId, String scope, UUID documentId, String title, String body) {
        return jdbcClient
                .sql("insert into memo (project_id, scope, document_id, title, body) "
                        + "values (:project, :scope, :document, :title, :body) returning " + COLUMNS)
                .param("project", projectId).param("scope", scope).param("document", documentId)
                .param("title", title).param("body", body)
                .query(MemoRepository::mapRow)
                .single();
    }

    /** 소유권은 프로젝트에 있다. 메모 id 만으로 찾으면 남의 메모가 보인다. */
    Optional<MemoDtos.Memo> find(UUID ownerUserId, UUID memoId) {
        return jdbcClient
                .sql("""
                        select m.id, m.project_id, m.scope, m.document_id, m.title, m.body,
                               m.created_at, m.updated_at
                        from memo m
                        join projects p on p.id = m.project_id
                        where m.id = :id and p.owner_user_id = :owner
                        """)
                .param("id", memoId).param("owner", ownerUserId)
                .query(MemoRepository::mapRow)
                .optional();
    }

    MemoDtos.Memo update(UUID memoId, String title, String body) {
        return jdbcClient
                .sql("update memo set title = :title, body = :body, updated_at = now() "
                        + "where id = :id returning " + COLUMNS)
                .param("id", memoId).param("title", title).param("body", body)
                .query(MemoRepository::mapRow)
                .single();
    }

    void delete(UUID memoId) {
        jdbcClient.sql("delete from memo where id = :id").param("id", memoId).update();
    }

    private static MemoDtos.Memo mapRow(ResultSet rows, int rowNum) throws SQLException {
        return new MemoDtos.Memo(
                rows.getObject("id", UUID.class),
                rows.getObject("project_id", UUID.class),
                rows.getString("scope").toLowerCase(),
                rows.getObject("document_id", UUID.class),
                rows.getString("title"),
                rows.getString("body"),
                rows.getObject("created_at", OffsetDateTime.class),
                rows.getObject("updated_at", OffsetDateTime.class));
    }
}
