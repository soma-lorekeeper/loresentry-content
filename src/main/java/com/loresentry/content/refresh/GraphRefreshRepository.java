package com.loresentry.content.refresh;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.loresentry.content.event.GraphRefreshRequested;
import com.loresentry.content.manuscript.Manuscripts;
import com.loresentry.content.manuscript.Purpose;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 최신화 요청을 기록하고, 다음 요청에 보낼 문서를 고른다. */
@Repository
public class GraphRefreshRepository {

    private final JdbcClient jdbcClient;

    private final Manuscripts manuscripts;

    GraphRefreshRepository(JdbcClient jdbcClient, Manuscripts manuscripts) {
        this.jdbcClient = jdbcClient;
        this.manuscripts = manuscripts;
    }

    /** 휴지통에 있는 프로젝트는 최신화하지 않는다. 남의 프로젝트와 같이 없는 것으로 답한다. */
    public boolean ownsActiveProject(UUID ownerUserId, UUID projectId) {
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
    public List<UUID> changedFiles(UUID projectId) {
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
    public Optional<String> openRunStatus(UUID projectId) {
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
    public UUID insertRun(UUID projectId, UUID requestedBy) {
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

    /** run 한 줄의 식별과 상태. */
    public record RunRow(UUID id, UUID projectId, String status) {
    }

    /**
     * 트랜잭션 없이 run 의 지금 상태를 본다. 완료 이벤트를 받았을 때 S3 를 읽을 가치가 있는지(아직 GENERATING 인지)
     * 미리 가르는 데만 쓴다. 상태를 바꾸기 전에는 {@link #lockRun} 으로 다시 확인한다.
     */
    public Optional<RunRow> peek(UUID runId) {
        return jdbcClient.sql("select id, project_id, status from refresh_runs where id = :id")
                .param("id", runId)
                .query(GraphRefreshRepository::mapRun)
                .optional();
    }

    /** run 행을 잠그고 읽는다. 완료 반영·시간 제한·확정이 같은 run 을 동시에 바꾸지 않게 한다. */
    public Optional<RunRow> lockRun(UUID runId) {
        return jdbcClient.sql("select id, project_id, status from refresh_runs where id = :id for update")
                .param("id", runId)
                .query(GraphRefreshRepository::mapRun)
                .optional();
    }

    /** 조회 API 가 보여 주는 run 의 상태와 시각, 실패 원인. */
    public record RunInfo(UUID id, String status, OffsetDateTime startedAt, OffsetDateTime readyAt,
            OffsetDateTime completedAt, String errorCode, String errorMessage) {
    }

    private static final String RUN_INFO = """
            select id, status, started_at, ready_at, completed_at, error_code, error_message from refresh_runs
            """;

    /** 이 프로젝트의 가장 최근 run. 폴링 응답이 보여 준다. */
    public Optional<RunInfo> latestInfo(UUID projectId) {
        return jdbcClient.sql(RUN_INFO + " where project_id = :project order by started_at desc, id desc limit 1")
                .param("project", projectId)
                .query(GraphRefreshRepository::mapInfo)
                .optional();
    }

    /** 이 프로젝트의 run 하나. 다른 프로젝트의 run 이면 비어 있다. */
    public Optional<RunInfo> findInfo(UUID projectId, UUID runId) {
        return jdbcClient.sql(RUN_INFO + " where id = :id and project_id = :project")
                .param("id", runId).param("project", projectId)
                .query(GraphRefreshRepository::mapInfo)
                .optional();
    }

    private static RunInfo mapInfo(ResultSet rows, int index) throws SQLException {
        return new RunInfo(rows.getObject("id", UUID.class), rows.getString("status"),
                rows.getObject("started_at", OffsetDateTime.class), rows.getObject("ready_at", OffsetDateTime.class),
                rows.getObject("completed_at", OffsetDateTime.class), rows.getString("error_code"),
                rows.getString("error_message"));
    }

    /**
     * 작업본을 저장하는 동안 run 이 끝나지(확정되지) 않게 공유 잠금을 걸고 상태를 읽는다. 확정은 run 을 배타 잠금하므로
     * 저장과 확정이 엇갈리지 않는다. 다른 프로젝트의 run 이면 비어 있다.
     */
    public Optional<String> statusForShare(UUID projectId, UUID runId) {
        return jdbcClient.sql("select status from refresh_runs where id = :id and project_id = :project for share")
                .param("id", runId).param("project", projectId)
                .query(String.class)
                .optional();
    }

    private static RunRow mapRun(ResultSet rows, int index) throws SQLException {
        return new RunRow(rows.getObject("id", UUID.class), rows.getObject("project_id", UUID.class),
                rows.getString("status"));
    }

    /** graph-rag 가 추출에 쓴 규칙 버전. 완료 이벤트가 알려 준다(실패여도 올 수 있다). */
    public void setPromptVersion(UUID runId, String promptVersion) {
        jdbcClient.sql("update refresh_runs set prompt_version = :version where id = :id")
                .param("version", promptVersion).param("id", runId).update();
    }

    /** 결과가 와서 검토할 작업본이 열렸다. */
    public void markReady(UUID runId) {
        jdbcClient.sql("update refresh_runs set status = 'READY', ready_at = now() where id = :id")
                .param("id", runId).update();
    }

    /**
     * run 을 끝낸다(NO_CHANGE·FAILED·APPLIED). 실패면 기계가 읽는 코드와 사람이 읽는 설명을 남긴다.
     *
     * @param code    실패 코드. 실패가 아니면 null
     * @param message 실패 설명. 실패가 아니면 null
     */
    public void markEnded(UUID runId, String status, String code, String message) {
        jdbcClient.sql("""
                        update refresh_runs
                        set status = :status, completed_at = now(), error_code = :code, error_message = :message
                        where id = :id
                        """)
                .param("status", status).param("code", code).param("message", message).param("id", runId)
                .update();
    }

    /**
     * graph-rag 결과 파일 원본을 남긴다. 같은 run 의 결과는 하나다 — 이미 있으면(재처리) 처음 것을 둔다.
     *
     * @param json 결과 파일 그대로. jsonb 는 바이트를 정규화하므로 원본 해시를 따로 남긴다
     */
    public void storeResult(UUID runId, String json, String sha256, String bucket, String key) {
        UUID projectId = jdbcClient.sql("select project_id from refresh_runs where id = :id")
                .param("id", runId).query(UUID.class).single();
        jdbcClient.sql("""
                        insert into refresh_results
                            (refresh_run_id, payload_enc, sha256, result_bucket, result_key)
                        values (:run, :payloadEnc, :sha256, :bucket, :key)
                        on conflict (refresh_run_id) do nothing
                        """)
                .param("run", runId).param("sha256", sha256)
                .param("payloadEnc", manuscripts.seal(projectId, runId, Purpose.REFRESH_RESULT, json))
                .param("bucket", bucket).param("key", key)
                .update();
    }

    /**
     * 시간 제한을 넘긴 GENERATING run 들을 잠그고 고른다. 다른 pod 가 같은 run 을 처리 중이면 건너뛴다
     * ({@code skip locked}) — 그 pod 가 끝낸다.
     */
    public List<UUID> lockOverdue(Duration timeout, int limit) {
        return jdbcClient.sql("""
                        select id from refresh_runs
                        where status = 'GENERATING' and started_at < now() - make_interval(secs => :seconds)
                        order by started_at
                        limit :limit
                        for update skip locked
                        """)
                .param("seconds", (double) timeout.toSeconds())
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** 이 run 이 S3 입력에 실은 문서와 revision. 다음 요청의 기준선이 된다. */
    public void insertRunFiles(UUID runId, List<GraphRefreshRequested.File> files) {
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
