package com.loresentry.content.refresh;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.loresentry.content.event.GraphRefreshRequested;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 최신화 요청을 기록하고, 다음 요청에 보낼 문서를 고른다. */
@Repository
class GraphRefreshRepository {

    private final JdbcClient jdbcClient;

    GraphRefreshRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** 휴지통에 있는 프로젝트는 최신화하지 않는다. 남의 프로젝트와 같이 없는 것으로 답한다. */
    boolean ownsActiveProject(UUID ownerUserId, UUID projectId) {
        return jdbcClient
                .sql("select 1 from projects where id = :id and owner_user_id = :owner and trashed_at is null")
                .param("id", projectId)
                .param("owner", ownerUserId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    /**
     * 이번 최신화에 보낼 문서. 지난 최신화의 기준선보다 revision 이 크거나, 기준선이 없는(한 번도 보내지 않은)
     * 활성 문서다. 폴더는 가리지 않는다.
     *
     * <p>기준선은 {@code refresh_run_files} 에서 문서마다 가장 큰 revision 이다. 단, 실패하거나 취소된 run 의
     * 기록은 세지 않는다 — 그 문서들은 graph-rag 가 처리하지 못했으므로 다시 보내야 한다.
     */
    List<UUID> changedFiles(UUID projectId) {
        return jdbcClient
                .sql("""
                        select d.id
                        from document d
                        -- 문서마다 지금까지 보낸 가장 큰 revision. 실패·취소된 run 의 기록은 빼고 센다.
                        left join (
                            select f.file_id, max(f.revision_no) as revision_no
                            from refresh_run_files f
                            join refresh_runs r on r.id = f.refresh_run_id
                            where r.project_id = :project and r.status not in ('FAILED', 'CANCELLED')
                            group by f.file_id
                        ) sent on sent.file_id = d.id
                        where d.project_id = :project
                          and d.trashed_at is null
                          -- 한 번도 보내지 않았거나(새 문서) 그 뒤로 저장된 문서
                          and (sent.revision_no is null or d.revision_no > sent.revision_no)
                        order by d.id
                        """)
                .param("project", projectId)
                .query(UUID.class)
                .list();
    }

    /**
     * 새 요청을 막는 run 의 상태. 결과를 기다리는 run(GENERATING)과 검토를 기다리는 run(READY)이 있으면 새 요청을
     * 받지 않는다. 어느 쪽인지에 따라 클라이언트에 알리는 이유가 다르다.
     */
    Optional<String> openRunStatus(UUID projectId) {
        return jdbcClient
                .sql("""
                        select status from refresh_runs
                        where project_id = :project and status in ('CAPTURING_BASE', 'GENERATING', 'READY')
                        limit 1
                        """)
                .param("project", projectId)
                .query(String.class)
                .optional();
    }

    /**
     * 진행 중인 run 으로 기록하고 그 id(= 요청 id)를 돌려준다. 같은 프로젝트에 진행 중인 run 이 이미 있으면
     * {@code uq_refresh_runs_one_in_progress} 때문에 {@code DuplicateKeyException} 이 난다.
     * {@code prompt_version} 은 graph-rag 가 완료 이벤트로 알려 줄 때 채운다.
     */
    UUID insertRun(UUID projectId, UUID requestedBy) {
        return jdbcClient
                .sql("""
                        insert into refresh_runs (project_id, requested_by, status)
                        values (:project, :user, 'GENERATING')
                        returning id
                        """)
                .param("project", projectId)
                .param("user", requestedBy)
                .query(UUID.class)
                .single();
    }

    /** 이 run 이 S3 입력에 실은 문서와 revision. 다음 요청의 기준선이 된다. */
    void insertRunFiles(UUID runId, List<GraphRefreshRequested.File> files) {
        for (GraphRefreshRequested.File file : files) {
            jdbcClient
                    .sql("insert into refresh_run_files (refresh_run_id, file_id, revision_no) "
                            + "values (:run, :file, :revision)")
                    .param("run", runId)
                    .param("file", file.fileId())
                    .param("revision", file.revisionNo())
                    .update();
        }
    }
}
