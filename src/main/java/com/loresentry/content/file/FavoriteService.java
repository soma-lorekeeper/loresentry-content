package com.loresentry.content.file;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.content.web.ContentFailure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteService {

    public record Favorites(@JsonProperty("file_ids") List<UUID> fileIds) {
    }

    private final JdbcClient jdbcClient;

    public FavoriteService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public Favorites list(UUID ownerUserId, UUID projectId) {
        requireProject(ownerUserId, projectId);
        return current(projectId);
    }

    /**
     * 이미 즐겨찾기면 아무 일도 하지 않는다. 같은 별을 두 번 눌러도 같은 결과여야 하고,
     * 화면이 목록을 낙관적으로 갱신한 뒤 재시도할 수 있다.
     */
    @Transactional
    public Favorites add(UUID ownerUserId, UUID projectId, UUID fileId) {
        requireProject(ownerUserId, projectId);
        if (!activeDocument(projectId, fileId)) {
            // 휴지통 문서를 즐겨찾기에 두면 열 수 없는 바로가기가 사이드바에 남는다.
            throw new ContentFailure(ContentFailure.Reason.FILE_NOT_FOUND);
        }

        jdbcClient
                .sql("insert into favorite (project_id, document_id) values (:project, :document) "
                        + "on conflict do nothing")
                .param("project", projectId).param("document", fileId)
                .update();
        return current(projectId);
    }

    @Transactional
    public Favorites remove(UUID ownerUserId, UUID projectId, UUID fileId) {
        requireProject(ownerUserId, projectId);
        jdbcClient
                .sql("delete from favorite where project_id = :project and document_id = :document")
                .param("project", projectId).param("document", fileId)
                .update();
        return current(projectId);
    }

    /**
     * 휴지통 문서는 목록에서 뺀다. 행은 남겨 두어 복원하면 즐겨찾기도 돌아온다 —
     * 지우면 사용자가 복원 후 다시 별을 눌러야 한다.
     */
    private Favorites current(UUID projectId) {
        return new Favorites(jdbcClient
                .sql("""
                        select f.document_id
                        from favorite f
                        join document d on d.id = f.document_id
                        where f.project_id = :project and d.trashed_at is null
                        order by f.created_at, f.document_id
                        """)
                .param("project", projectId)
                .query(UUID.class)
                .list());
    }

    private void requireProject(UUID ownerUserId, UUID projectId) {
        boolean exists = jdbcClient
                .sql("select 1 from projects where id = :id and owner_user_id = :owner"
                        + " and trashed_at is null")
                .param("id", projectId).param("owner", ownerUserId)
                .query(Integer.class).optional().isPresent();
        if (!exists) {
            throw new ContentFailure(ContentFailure.Reason.PROJECT_NOT_FOUND);
        }
    }

    private boolean activeDocument(UUID projectId, UUID fileId) {
        return jdbcClient
                .sql("select 1 from document where id = :id and project_id = :project"
                        + " and trashed_at is null")
                .param("id", fileId).param("project", projectId)
                .query(Integer.class).optional().isPresent();
    }
}
