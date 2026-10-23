package com.loresentry.content.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.media.MediaStorageService;
import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkClientException;
import tools.jackson.databind.JsonNode;

/**
 * UserDeleted 를 받으면 그 사용자의 콘텐츠가 지워지고, 지운 프로젝트마다 ProjectDeleted 가 outbox 에 남는지 본다.
 * 처리는 부르는 쪽이 연 트랜잭션 안에서만 돈다 — 처리 기록 없이 처리만 되는 길이 막혀 있는지도 확인한다.
 *
 * <p>inbox 의 InboxTransaction 이 하는 일(트랜잭션을 열고 commit)을 {@link TransactionTemplate} 이 대신한다.
 * outbox_events 는 테스트 사이에 지워지지 않으므로 aggregate_id 로 거른다.
 */
class UserDeletedServiceTest extends ApiTestSupport {

    /** 테스트 대상. Spring 이 만든 것이라 트랜잭션 규칙을 지키는 대리 객체를 거쳐 불린다. */
    @Autowired
    UserDeletedService service;

    /** 코드로 트랜잭션을 여는 도구. inbox 의 InboxTransaction 이 하는 일을 대신한다. */
    @Autowired
    TransactionTemplate transaction;

    /** S3 를 부르지 않고, commit 뒤에 어느 키를 지우려 했는지만 본다. */
    @MockitoBean
    private MediaStorageService storage;

    private record Seeded(UUID project, UUID document, String imageKey) {
    }

    /** outbox 에 쌓인 ProjectDeleted 하나. */
    private record Tombstone(String key, JsonNode payload) {
    }

    @Test
    void refusesToRunWithoutATransaction() {
        assertThatThrownBy(() -> service.handle(new UserDeletedEvent(UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void runsInsideTheCallersTransaction() {
        assertThatCode(() -> handle(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void deletesEveryProjectOfTheUserActiveOrTrashedAndNothingElse() throws Exception {
        Seeded active = seed(owner, "진행 중", false);
        Seeded trashed = seed(owner, "휴지통", true);
        Seeded others = seed(stranger, "남의 원고", false);
        assertThat(rowsOf(active)).isGreaterThanOrEqualTo(9);
        long othersBefore = rowsOf(others);

        handle(owner);

        assertThat(rowsOf(active)).isZero();
        assertThat(rowsOf(trashed)).isZero();
        assertThat(rowsOf(others)).isEqualTo(othersBefore);
        assertThat(jdbcClient.sql("select count(*) from workspace_state where owner_user_id = :u")
                .param("u", owner).query(Long.class).single()).isZero();

        mockMvc.perform(as(get("/projects/" + others.project()), stranger))
                .andExpect(status().isOk());
        mockMvc.perform(as(get("/projects")))
                .andExpect(jsonPath("$.projects.length()").value(0));
        mockMvc.perform(as(get("/projects/trash")))
                .andExpect(jsonPath("$.projects.length()").value(0));

        verify(storage).delete(active.imageKey());
        verify(storage).delete(trashed.imageKey());
        verify(storage, never()).delete(others.imageKey());
    }

    /** feedback 은 내부 기록이라 탈퇴한 사용자의 것도 남는다. */
    @Test
    void keepsTheFeedbackOfTheUserThatLeft() throws Exception {
        mockMvc.perform(as(body(post("/feedback"), "{\"category\":\"BUG\",\"message\":\"내 의견\"}")))
                .andExpect(status().isCreated());

        handle(owner);

        assertThat(feedbackOf(owner)).isEqualTo(1);
    }

    /** 지운 프로젝트마다 tombstone 하나다. 활성·휴지통을 가리지 않고, 남의 프로젝트에는 내지 않는다. */
    @Test
    void publishesOneOwnerDeletedTombstonePerDeletedProject() throws Exception {
        Seeded active = seed(owner, "진행 중", false);
        Seeded trashed = seed(owner, "휴지통", true);
        Seeded others = seed(stranger, "남의 원고", false);

        handle(owner);

        for (Seeded seeded : List.of(active, trashed)) {
            assertThat(tombstonesOf(seeded.project())).containsExactly(new Tombstone(
                    seeded.project().toString(),
                    json.readTree("""
                            {"project_id":"%s","reason":"OWNER_DELETED"}
                            """.formatted(seeded.project()))));
        }
        assertThat(tombstonesOf(others.project())).isEmpty();
    }

    /** 같은 사용자의 이벤트가 다시 와도 지울 것이 없어 아무것도 더 하지 않는다. */
    @Test
    void handlingTheSameUserTwiceChangesNothingMore() throws Exception {
        Seeded seeded = seed(owner, "한 번만", false);

        handle(owner);
        handle(owner);

        verify(storage, times(1)).delete(seeded.imageKey());
        verify(storage, times(1)).delete(anyString());
        assertThat(tombstonesOf(seeded.project())).hasSize(1);
    }

    @Test
    void deletesObjectsOnlyAfterTheRowsAreCommitted() throws Exception {
        Seeded seeded = seed(owner, "커밋 뒤", false);
        List<Long> visibleProjects = new ArrayList<>();
        willAnswer(invocation -> {
            // 다른 연결에서 보인다. 커밋 전이라면 행이 아직 보인다.
            visibleProjects.add(count("projects", "id", seeded.project()));
            return null;
        }).given(storage).delete(anyString());

        handle(owner);

        assertThat(visibleProjects).containsExactly(0L);
    }

    @Test
    void keepsTheCommittedPurgeWhenStorageFails() throws Exception {
        Seeded first = seed(owner, "첫째", false);
        Seeded second = seed(owner, "둘째", false);
        willThrow(SdkClientException.create("unreachable")).given(storage).delete(first.imageKey());

        assertThatCode(() -> handle(owner)).doesNotThrowAnyException();

        assertThat(rowsOf(first)).isZero();
        assertThat(rowsOf(second)).isZero();
        verify(storage).delete(second.imageKey());
    }

    /** InboxTransaction 처럼 트랜잭션을 열어 처리하고 commit 한다. */
    private void handle(UUID user) {
        transaction.executeWithoutResult(status -> service.handle(new UserDeletedEvent(user)));
    }

    /** 탈퇴가 지워야 할 것을 한 프로젝트에 빠짐없이 만든다. */
    private Seeded seed(UUID user, String name, boolean trashed) throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"" + name + "\"}"), user))
                .andExpect(status().isCreated())
                .andReturn();
        UUID project = UUID.fromString(read(created).get("id").stringValue());

        mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"episode\",\"title\":\"1부\"}"), user))
                .andExpect(status().isCreated());
        MvcResult file = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"CHARACTER\",\"title\":\"서윤\"}"), user))
                .andExpect(status().isCreated())
                .andReturn();
        UUID document = UUID.fromString(read(file).get("id").stringValue());

        mockMvc.perform(as(body(put("/files/" + document + "/content"), """
                        {"title":"서윤","body":{"schema_version":1,"doc":{"type":"doc","content":[
                          {"type":"paragraph","content":[{"type":"text","text":"기록관"}]}]}},
                         "properties":[{"key":"description","value":"막내"}],"relations":[]}
                        """), user).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
        mockMvc.perform(as(body(post("/projects/" + project + "/memos"),
                        "{\"scope\":\"file\",\"document_id\":\"" + document + "\",\"body\":\"메모\"}"), user))
                .andExpect(status().isCreated());
        mockMvc.perform(as(put("/projects/" + project + "/favorites/" + document), user))
                .andExpect(status().isOk());
        mockMvc.perform(as(body(put("/projects/" + project + "/workspace-state"),
                        "{\"layout\":{\"tabs\":[]}}"), user))
                .andExpect(status().isNoContent());

        String key = "projects/" + project + "/images/" + UUID.randomUUID() + ".png";
        jdbcClient.sql("insert into image (project_id, s3_key, content_type, size_bytes) "
                        + "values (:project, :key, 'image/png', 10)")
                .param("project", project).param("key", key).update();

        if (trashed) {
            mockMvc.perform(as(post("/projects/" + project + "/trash"), user))
                    .andExpect(status().isNoContent());
        }
        return new Seeded(project, document, key);
    }

    private long count(String table, String column, UUID project) {
        return jdbcClient.sql("select count(*) from " + table + " where " + column + " = :id")
                .param("id", project).query(Long.class).single();
    }

    private long feedbackOf(UUID user) {
        return jdbcClient.sql("select count(*) from feedback where user_id = :u")
                .param("u", user).query(Long.class).single();
    }

    private long rowsOf(Seeded seeded) {
        long total = 0;
        for (String table : List.of("projects:id", "document:project_id", "episode_folders:project_id",
                "image:project_id", "memo:project_id", "favorite:project_id", "workspace_state:project_id")) {
            String[] parts = table.split(":");
            total += count(parts[0], parts[1], seeded.project());
        }
        total += count("document_versions", "document_id", seeded.document());
        total += count("document_properties", "document_id", seeded.document());
        return total;
    }

    /** 이 프로젝트를 aggregate 로 하는 ProjectDeleted 를 seq 순으로. */
    private List<Tombstone> tombstonesOf(UUID project) {
        return jdbcClient
                .sql("select event_key, payload::text as payload from outbox_events"
                        + " where event_type = 'ProjectDeleted' and aggregate_id = :id order by seq")
                .param("id", project)
                .query((rows, index) -> new Tombstone(rows.getString("event_key"),
                        json.readTree(rows.getString("payload"))))
                .list();
    }
}
