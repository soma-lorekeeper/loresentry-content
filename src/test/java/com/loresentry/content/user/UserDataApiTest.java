package com.loresentry.content.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.core.exception.SdkClientException;

class UserDataApiTest extends ApiTestSupport {

    @MockitoBean
    private MediaStorageService storage;

    private record Seeded(UUID project, UUID document, String imageKey) {
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

    @Test
    void deletesEveryProjectOfTheUserActiveOrTrashedAndNothingElse() throws Exception {
        Seeded active = seed(owner, "진행 중", false);
        Seeded trashed = seed(owner, "휴지통", true);
        Seeded others = seed(stranger, "남의 원고", false);
        mockMvc.perform(as(body(post("/feedback"), "{\"category\":\"BUG\",\"message\":\"내 의견\"}")))
                .andExpect(status().isCreated());
        mockMvc.perform(as(body(post("/feedback"), "{\"category\":\"IDEA\",\"message\":\"남의 의견\"}"),
                        stranger))
                .andExpect(status().isCreated());
        assertThat(feedbackOf(owner)).isEqualTo(1);
        assertThat(rowsOf(active)).isGreaterThanOrEqualTo(9);
        long othersBefore = rowsOf(others);

        mockMvc.perform(as(delete("/users/me/data")))
                .andExpect(status().isNoContent());

        assertThat(rowsOf(active)).isZero();
        assertThat(rowsOf(trashed)).isZero();
        assertThat(rowsOf(others)).isEqualTo(othersBefore);
        assertThat(jdbcClient.sql("select count(*) from workspace_state where owner_user_id = :u")
                .param("u", owner).query(Long.class).single()).isZero();
        assertThat(feedbackOf(owner)).isZero();
        assertThat(feedbackOf(stranger)).isEqualTo(1);

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

    @Test
    void answersTheSameWhenThereIsNothingLeft() throws Exception {
        Seeded seeded = seed(owner, "한 번만", false);

        mockMvc.perform(as(delete("/users/me/data"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/users/me/data"))).andExpect(status().isNoContent());

        verify(storage, times(1)).delete(seeded.imageKey());
        verify(storage, times(1)).delete(anyString());
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

        mockMvc.perform(as(delete("/users/me/data"))).andExpect(status().isNoContent());

        assertThat(visibleProjects).containsExactly(0L);
    }

    @Test
    void keepsTheCommittedPurgeWhenStorageFails() throws Exception {
        Seeded first = seed(owner, "첫째", false);
        Seeded second = seed(owner, "둘째", false);
        willThrow(SdkClientException.create("unreachable")).given(storage).delete(first.imageKey());

        mockMvc.perform(as(delete("/users/me/data"))).andExpect(status().isNoContent());

        assertThat(rowsOf(first)).isZero();
        assertThat(rowsOf(second)).isZero();
        verify(storage).delete(second.imageKey());
    }

    @Test
    void needsTheCallersIdentity() throws Exception {
        seed(owner, "익명 불가", false);

        mockMvc.perform(delete("/users/me/data"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"));

        assertThat(jdbcClient.sql("select count(*) from projects").query(Long.class).single()).isEqualTo(1);
        verify(storage, never()).delete(anyString());
    }
}
