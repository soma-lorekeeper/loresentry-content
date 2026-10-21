package com.loresentry.content.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class FileEventReplayTest extends ApiTestSupport {

    @Test
    void writesOneChangeEventPerActiveDocument() throws Exception {
        UUID project = project("다시 보낼 작품");
        UUID chapter = document(project, "MANUSCRIPT", "1화");
        UUID lena = document(project, "CHARACTER", "레나");
        UUID trashed = document(project, "CHARACTER", "버린 인물");
        mockMvc.perform(as(post("/files/" + trashed + "/trash"))).andExpect(status().isNoContent());
        jdbcClient.sql("delete from outbox_events").update();

        mockMvc.perform(post("/internal/admin/file-events/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").value(1))
                .andExpect(jsonPath("$.documents").value(2))
                .andExpect(jsonPath("$.last").value(project.toString()));

        List<Map<String, Object>> rows = jdbcClient.sql("""
                        select aggregate_id, payload->>'change_type' as change_type,
                               (payload->>'revision_no')::bigint as revision_no
                        from outbox_events where event_type = 'FileChanged' order by aggregate_id
                        """)
                .query().listOfRows();
        assertThat(rows).extracting(row -> row.get("aggregate_id"))
                .containsExactlyInAnyOrder(chapter, lena);
        assertThat(rows).extracting(row -> row.get("change_type")).containsOnly("UPDATED");
    }

    @Test
    void pagesThroughProjectsInIdOrder() throws Exception {
        UUID first = project("첫 작품");
        UUID second = project("둘째 작품");
        document(first, "MANUSCRIPT", "1화");
        document(second, "MANUSCRIPT", "1화");
        List<UUID> ordered = first.compareTo(second) < 0 ? List.of(first, second) : List.of(second, first);

        JsonNode page = read(mockMvc.perform(post("/internal/admin/file-events/replay").param("limit", "1"))
                .andExpect(status().isOk()).andReturn());
        assertThat(page.get("projects").intValue()).isEqualTo(1);
        assertThat(page.get("last").stringValue()).isEqualTo(ordered.get(0).toString());

        page = read(mockMvc.perform(post("/internal/admin/file-events/replay")
                        .param("after", ordered.get(0).toString()).param("limit", "1"))
                .andExpect(status().isOk()).andReturn());
        assertThat(page.get("last").stringValue()).isEqualTo(ordered.get(1).toString());

        mockMvc.perform(post("/internal/admin/file-events/replay").param("after", ordered.get(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects").value(0))
                .andExpect(jsonPath("$.last").isEmpty());
    }

    private UUID project(String name) throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"" + name + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(created).get("id").stringValue());
    }

    private UUID document(UUID project, String folderCode, String title) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        json.writeValueAsString(Map.of("kind", "document", "folder_code", folderCode,
                                "title", title)))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }
}
