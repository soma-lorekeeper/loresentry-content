package com.loresentry.content.refresh;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S3 read is bounded; Inbox + candidate JSON + state commit in one DB transaction. */
@Service
public class GraphRefreshCompletedService {
    private final JdbcClient jdbc;
    private final RefreshObjects objects;
    private final JsonMapper json = JsonMapper.builder().build();

    public GraphRefreshCompletedService(JdbcClient jdbc, RefreshObjects objects) {
        this.jdbc = jdbc;
        this.objects = objects;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(GraphRefreshCompletedEvent event) {
        var rows =
                jdbc.sql(
                                """
                                select status,input_fingerprint from refresh_runs
                                where id=:request and project_id=:project for update
                                """)
                        .param("request", event.requestId())
                        .param("project", event.projectId())
                        .query(
                                (rs, n) ->
                                        new String[] {
                                            rs.getString("status"),
                                            rs.getString("input_fingerprint")
                                        })
                        .list();
        // Missing/deleted/cancelled runs and repeated completion cannot recreate proposals.
        if (rows.isEmpty()) {
            objects.discardAfterCommit(event);
            return;
        }
        if (!"GENERATING".equals(rows.getFirst()[0])) {
            if (!"READY".equals(rows.getFirst()[0])) objects.discardAfterCommit(event);
            return;
        }
        boolean succeeded = event.outcome() == GraphRefreshCompletedEvent.Outcome.SUCCEEDED;
        if ((succeeded && (event.result() == null || event.error() != null))
                || (!succeeded && event.error() == null))
            throw new IllegalArgumentException("Invalid completion outcome");
        String body = null;
        if (event.result() != null) {
            byte[] bytes =
                    objects.result(
                            event.projectId().toString(),
                            event.requestId().toString(),
                            event.result().bucket(),
                            event.result().key());
            JsonNode result = json.readTree(bytes);
            String outcome = result.path("outcome").asString("");
            if (!"refresh-result-v3".equals(result.path("schema_version").asString())
                    || !event.requestId().toString().equals(result.path("request_id").asString())
                    || !event.projectId().toString().equals(result.path("project_id").asString())
                    || !java.util.Objects.equals(
                            rows.getFirst()[1],
                            result.path("execution").path("input_fingerprint").asString())
                    || (succeeded && !("PROPOSED".equals(outcome) || "NO_CHANGE".equals(outcome)))
                    || (!succeeded && !"FAILED".equals(outcome)))
                throw new IllegalArgumentException("Invalid refresh result identity");
            body = json.writeValueAsString(result);
            jdbc.sql(
                            "insert into refresh_results(refresh_run_id,payload)"
                                + " values(:request,:body::jsonb)")
                    .param("request", event.requestId())
                    .param("body", body)
                    .update();
        }
        jdbc.sql(
                        """
update refresh_runs set status=:status,ready_at=case when :status='READY' then now() else null end,
    completed_at=case when :status='FAILED' then now() else null end,
    prompt_version=coalesce(:prompt,prompt_version),error_message=:error,
    result_bucket=:bucket,result_key=:key
where id=:request and project_id=:project and status='GENERATING'
""")
                .param("status", succeeded ? "READY" : "FAILED")
                .param("request", event.requestId())
                .param("project", event.projectId())
                .param("prompt", event.promptVersion())
                .param("error", succeeded ? null : event.error().code())
                .param("bucket", event.result() == null ? null : event.result().bucket())
                .param("key", event.result() == null ? null : event.result().key())
                .update();
        // Never apply document/new-document/relation proposals without user approval.
    }
}
