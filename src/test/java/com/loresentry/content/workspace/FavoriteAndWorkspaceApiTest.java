package com.loresentry.content.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class FavoriteAndWorkspaceApiTest extends ApiTestSupport {

    private UUID project;

    @BeforeEach
    void seed() throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"작업공간\"}")))
                .andReturn();
        this.project = UUID.fromString(read(created).get("id").stringValue());
    }

    private UUID document(String title) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"CHARACTER\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private List<String> favoriteIds(MvcResult result) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : read(result).get("file_ids")) {
            ids.add(node.stringValue());
        }
        return ids;
    }

    private List<String> favorites() throws Exception {
        return favoriteIds(mockMvc.perform(as(get("/projects/" + project + "/favorites")))
                .andExpect(status().isOk()).andReturn());
    }

    @Test
    void startsWithNoFavorites() throws Exception {
        assertThat(favorites()).isEmpty();
    }

    @Test
    void addsAndRemovesAndReturnsTheWholeListEachTime() throws Exception {
        UUID first = document("유중혁");
        UUID second = document("김독자");

        assertThat(favoriteIds(mockMvc.perform(as(put("/projects/" + project + "/favorites/" + first)))
                .andExpect(status().isOk()).andReturn()))
                .containsExactly(first.toString());
        assertThat(favoriteIds(mockMvc.perform(as(put("/projects/" + project + "/favorites/" + second)))
                .andExpect(status().isOk()).andReturn()))
                .containsExactly(first.toString(), second.toString());
        assertThat(favoriteIds(mockMvc.perform(as(delete("/projects/" + project + "/favorites/" + first)))
                .andExpect(status().isOk()).andReturn()))
                .containsExactly(second.toString());
    }

    @Test
    void treatsARepeatedStarAsTheSameOutcome() throws Exception {
        UUID file = document("유중혁");

        mockMvc.perform(as(put("/projects/" + project + "/favorites/" + file)))
                .andExpect(status().isOk());
        // 같은 별을 두 번 눌러도 결과가 같아야 화면이 낙관적으로 갱신한 뒤 재시도할 수 있다.
        mockMvc.perform(as(put("/projects/" + project + "/favorites/" + file)))
                .andExpect(status().isOk());
        assertThat(favorites()).containsExactly(file.toString());

        mockMvc.perform(as(delete("/projects/" + project + "/favorites/" + file)))
                .andExpect(status().isOk());
        mockMvc.perform(as(delete("/projects/" + project + "/favorites/" + file)))
                .andExpect(status().isOk());
        assertThat(favorites()).isEmpty();
    }

    @Test
    void refusesToStarSomethingThatCannotBeOpened() throws Exception {
        UUID file = document("유중혁");
        mockMvc.perform(as(post("/files/" + file + "/trash"))).andExpect(status().isNoContent());

        mockMvc.perform(as(put("/projects/" + project + "/favorites/" + file)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void hidesTrashedFilesButBringsThemBackOnRestore() throws Exception {
        UUID file = document("유중혁");
        mockMvc.perform(as(put("/projects/" + project + "/favorites/" + file)))
                .andExpect(status().isOk());

        mockMvc.perform(as(post("/files/" + file + "/trash"))).andExpect(status().isNoContent());
        assertThat(favorites()).isEmpty();

        // 행을 지우지 않았으므로 복원하면 즐겨찾기도 돌아온다 — 다시 별을 누르게 하지 않는다.
        mockMvc.perform(as(post("/files/" + file + "/restore"))).andExpect(status().isOk());
        assertThat(favorites()).containsExactly(file.toString());
    }

    @Test
    void dropsFavoritesWhenTheFileIsDeletedForGood() throws Exception {
        UUID file = document("유중혁");
        mockMvc.perform(as(put("/projects/" + project + "/favorites/" + file)))
                .andExpect(status().isOk());
        mockMvc.perform(as(post("/files/" + file + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/files/" + file))).andExpect(status().isNoContent());

        assertThat(jdbcClient.sql("select count(*) from favorite where project_id = :project")
                .param("project", project).query(Long.class).single()).isZero();
    }

    @Test
    void hidesAnotherOwnersFavorites() throws Exception {
        UUID file = document("유중혁");

        mockMvc.perform(as(get("/projects/" + project + "/favorites"), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mockMvc.perform(as(put("/projects/" + project + "/favorites/" + file), stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsNoLayoutForAProjectOpenedForTheFirstTime() throws Exception {
        // 복원할 것이 없는 것은 오류가 아니다.
        mockMvc.perform(as(get("/projects/" + project + "/workspace-state")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layout").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void storesTheLayoutWithoutInterpretingIt() throws Exception {
        String layout = "{\"panes\":[{\"tabs\":[{\"target\":{\"kind\":\"file\",\"fileId\":\"x\"}}],"
                + "\"activeTabId\":\"t1\"}],\"sidebarOpen\":true,\"panels\":{\"memoDock\":\"right\"}}";

        mockMvc.perform(as(body(put("/projects/" + project + "/workspace-state"),
                        "{\"layout\":" + layout + "}")))
                .andExpect(status().isNoContent());

        mockMvc.perform(as(get("/projects/" + project + "/workspace-state")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layout.sidebarOpen").value(true))
                .andExpect(jsonPath("$.layout.panels.memoDock").value("right"))
                .andExpect(jsonPath("$.layout.panes[0].tabs[0].target.fileId").value("x"));
    }

    @Test
    void replacesTheLayoutOnEverySave() throws Exception {
        mockMvc.perform(as(body(put("/projects/" + project + "/workspace-state"),
                        "{\"layout\":{\"sidebarOpen\":true}}"))).andExpect(status().isNoContent());
        mockMvc.perform(as(body(put("/projects/" + project + "/workspace-state"),
                        "{\"layout\":{\"sidebarOpen\":false}}"))).andExpect(status().isNoContent());

        mockMvc.perform(as(get("/projects/" + project + "/workspace-state")))
                .andExpect(jsonPath("$.layout.sidebarOpen").value(false));
    }

    @Test
    void keepsEachUsersLayoutApart() throws Exception {
        mockMvc.perform(as(body(put("/projects/" + project + "/workspace-state"),
                        "{\"layout\":{\"whose\":\"mine\"}}"))).andExpect(status().isNoContent());

        // 다른 사용자는 이 프로젝트를 볼 수 없으므로 레이아웃도 볼 수 없다. 키에 사용자가 들어 있어
        // 나중에 한 프로젝트를 여럿이 열게 되어도 서로의 탭을 덮어쓰지 않는다.
        mockMvc.perform(as(get("/projects/" + project + "/workspace-state"), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void refusesAMissingOrNullLayout() throws Exception {
        mockMvc.perform(as(body(put("/projects/" + project + "/workspace-state"), "{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(as(body(put("/projects/" + project + "/workspace-state"),
                        "{\"layout\":null}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dropsTheLayoutWithTheProject() throws Exception {
        mockMvc.perform(as(body(put("/projects/" + project + "/workspace-state"),
                        "{\"layout\":{\"a\":1}}"))).andExpect(status().isNoContent());
        mockMvc.perform(as(post("/projects/" + project + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/projects/" + project))).andExpect(status().isNoContent());

        assertThat(jdbcClient.sql("select count(*) from workspace_state where project_id = :project")
                .param("project", project).query(Long.class).single()).isZero();
    }
}
