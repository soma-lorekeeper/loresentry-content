package com.loresentry.content.workspace;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.loresentry.content.web.ContentFailure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkspaceStateService {

    /** 레이아웃 JSON 의 상한. 탭 목록과 패널 크기라 수 KB 를 넘을 이유가 없다. */
    static final int LAYOUT_MAX = 100_000;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record State(JsonNode layout) {
    }

    private final JdbcClient jdbcClient;

    private final JsonMapper json = JsonMapper.builder().build();

    public WorkspaceStateService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public State load(UUID ownerUserId, UUID projectId) {
        requireProject(ownerUserId, projectId);

        return jdbcClient
                .sql("select layout from workspace_state "
                        + "where project_id = :project and owner_user_id = :owner")
                .param("project", projectId).param("owner", ownerUserId)
                .query((rows, index) -> new State(json.readTree(rows.getString("layout"))))
                .optional()
                // 처음 여는 프로젝트다. 복원할 것이 없는 것은 오류가 아니다.
                .orElse(new State(null));
    }

    /** 사용자당 프로젝트당 한 행이므로 upsert 다. 저장 요청이 서로를 덮어쓰는 것이 의도된 동작이다. */
    @Transactional
    public void save(UUID ownerUserId, UUID projectId, State state) {
        requireProject(ownerUserId, projectId);
        if (state == null || state.layout() == null || state.layout().isNull()) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }

        String layout = json.writeValueAsString(state.layout());
        if (layout.length() > LAYOUT_MAX) {
            throw new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
        }

        jdbcClient
                .sql("""
                        insert into workspace_state (project_id, owner_user_id, layout)
                        values (:project, :owner, :layout::jsonb)
                        on conflict (project_id, owner_user_id)
                        do update set layout = excluded.layout, updated_at = now()
                        """)
                .param("project", projectId).param("owner", ownerUserId).param("layout", layout)
                .update();
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
}
