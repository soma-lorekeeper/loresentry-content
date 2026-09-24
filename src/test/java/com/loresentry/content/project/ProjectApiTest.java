package com.loresentry.content.project;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.loresentry.content.web.CurrentUserArgumentResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ProjectApiTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    private final JsonMapper json = JsonMapper.builder().build();

    private final UUID owner = UUID.randomUUID();

    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void clearProjects() {
        jdbcClient.sql("delete from projects").update();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID user) {
        return request.header(CurrentUserArgumentResolver.HEADER, user.toString());
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String payload) {
        return request.contentType(MediaType.APPLICATION_JSON).content(payload);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID createProject(String name) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects"),
                        "{\"name\":\"" + name + "\"}"), owner))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    @Test
    void createsAProjectAndReturnsItsLocation() throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects"),
                        "{\"name\":\"  유리 정원의 기록  \",\"description\":\"  유리 온실  \"}"), owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("유리 정원의 기록"))
                .andExpect(jsonPath("$.description").value("유리 온실"))
                .andExpect(jsonPath("$.trashed_at").value(nullValue()))
                .andExpect(jsonPath("$.last_worked_at").exists())
                .andExpect(jsonPath("$.last_file").value(nullValue()))
                .andReturn();

        String id = read(result).get("id").stringValue();
        mockMvc.perform(as(post("/projects"), owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(header().exists("Location"));
        mockMvc.perform(as(get("/projects/" + id), owner)).andExpect(status().isOk());
    }

    @Test
    void createsWithoutADescription() throws Exception {
        mockMvc.perform(as(body(post("/projects"), "{\"name\":\"설명 없음\"}"), owner))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value(""));
    }

    @Test
    void rejectsABlankOrOverlongName() throws Exception {
        mockMvc.perform(as(body(post("/projects"), "{\"name\":\"   \"}"), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROJECT_NAME"))
                .andExpect(jsonPath("$.next_action").value("NONE"));

        mockMvc.perform(as(body(post("/projects"), "{\"description\":\"이름이 없다\"}"), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROJECT_NAME"));

        mockMvc.perform(as(body(post("/projects"),
                        "{\"name\":\"" + "가".repeat(ProjectService.NAME_MAX + 1) + "\"}"), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROJECT_NAME"));
    }

    @Test
    void acceptsANameAtTheLimit() throws Exception {
        mockMvc.perform(as(body(post("/projects"),
                        "{\"name\":\"" + "가".repeat(ProjectService.NAME_MAX) + "\"}"), owner))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsAnOverlongDescription() throws Exception {
        mockMvc.perform(as(body(post("/projects"),
                        "{\"name\":\"설명이 길다\",\"description\":\""
                                + "가".repeat(ProjectService.DESCRIPTION_MAX + 1) + "\"}"), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROJECT_DESCRIPTION"));
    }

    @Test
    void rejectsADuplicateActiveNameIgnoringCase() throws Exception {
        createProject("Glass Garden");

        mockMvc.perform(as(body(post("/projects"), "{\"name\":\"glass garden\"}"), owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NAME_TAKEN"));

        // 다른 사용자에게는 같은 이름이 열려 있다.
        mockMvc.perform(as(body(post("/projects"), "{\"name\":\"Glass Garden\"}"), stranger))
                .andExpect(status().isCreated());
    }

    @Test
    void freesTheNameWhileTheProjectIsInTheTrash() throws Exception {
        UUID trashed = createProject("Winter Notes");
        mockMvc.perform(as(post("/projects/" + trashed + "/trash"), owner))
                .andExpect(status().isNoContent());

        createProject("Winter Notes");

        mockMvc.perform(as(post("/projects/" + trashed + "/restore"), owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NAME_TAKEN"));
    }

    @Test
    void listsActiveProjectsMostRecentlyWorkedFirst() throws Exception {
        UUID first = createProject("첫째");
        UUID second = createProject("둘째");
        mockMvc.perform(as(body(patch("/projects/" + first), "{\"description\":\"손을 댔다\"}"), owner))
                .andExpect(status().isOk());

        mockMvc.perform(as(get("/projects"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects.length()").value(2))
                .andExpect(jsonPath("$.projects[0].id").value(first.toString()))
                .andExpect(jsonPath("$.projects[1].id").value(second.toString()));
    }

    @Test
    void keepsTrashedProjectsOutOfTheActiveListAndOutOfReach() throws Exception {
        UUID project = createProject("사라질 것");
        mockMvc.perform(as(post("/projects/" + project + "/trash"), owner))
                .andExpect(status().isNoContent());

        mockMvc.perform(as(get("/projects"), owner))
                .andExpect(jsonPath("$.projects.length()").value(0));
        mockMvc.perform(as(get("/projects/trash"), owner))
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.projects[0].trashed_at").exists());

        // 휴지통 프로젝트는 열 수도 고칠 수도 없다.
        mockMvc.perform(as(get("/projects/" + project), owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mockMvc.perform(as(body(patch("/projects/" + project), "{\"name\":\"새 이름\"}"), owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void repeatsTrashAndRestoreWithoutChangingTheOutcome() throws Exception {
        UUID project = createProject("멱등");

        mockMvc.perform(as(post("/projects/" + project + "/trash"), owner))
                .andExpect(status().isNoContent());
        MvcResult afterFirst = mockMvc.perform(as(get("/projects/trash"), owner)).andReturn();
        String trashedAt = read(afterFirst).get("projects").get(0).get("trashed_at").stringValue();

        mockMvc.perform(as(post("/projects/" + project + "/trash"), owner))
                .andExpect(status().isNoContent());
        MvcResult afterSecond = mockMvc.perform(as(get("/projects/trash"), owner)).andReturn();
        org.assertj.core.api.Assertions
                .assertThat(read(afterSecond).get("projects").get(0).get("trashed_at").stringValue())
                .isEqualTo(trashedAt);

        mockMvc.perform(as(post("/projects/" + project + "/restore"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trashed_at").value(nullValue()));
        mockMvc.perform(as(post("/projects/" + project + "/restore"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trashed_at").value(nullValue()));
    }

    @Test
    void updatesOnlyTheFieldsThatWereSent() throws Exception {
        UUID project = createProject("원래 이름");
        mockMvc.perform(as(body(patch("/projects/" + project),
                        "{\"name\":\"원래 이름\",\"description\":\"원래 설명\"}"), owner))
                .andExpect(status().isOk());

        mockMvc.perform(as(body(patch("/projects/" + project), "{\"name\":\"새 이름\"}"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 이름"))
                .andExpect(jsonPath("$.description").value("원래 설명"));

        mockMvc.perform(as(body(patch("/projects/" + project), "{\"description\":\"\"}"), owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 이름"))
                .andExpect(jsonPath("$.description").value(""));

        mockMvc.perform(as(body(patch("/projects/" + project), "{}"), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void deletesOnlyFromTheTrash() throws Exception {
        UUID project = createProject("지울 것");

        mockMvc.perform(as(delete("/projects/" + project), owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_TRASHED"));

        mockMvc.perform(as(post("/projects/" + project + "/trash"), owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/projects/" + project), owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/projects/" + project), owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesTheDocumentsAndEpisodesUnderTheProject() throws Exception {
        UUID project = createProject("딸린 것이 있다");
        UUID episode = jdbcClient
                .sql("insert into episode_folders (project_id, created_by_user_id, name, rank) "
                        + "values (:project, :user, '1부', 'a0') returning id")
                .param("project", project).param("user", owner)
                .query(UUID.class).single();
        jdbcClient.sql("insert into document (project_id, folder_id, episode_id, title, rank) "
                        + "values (:project, 4, :episode, '1화', 'a0')")
                .param("project", project).param("episode", episode)
                .update();

        mockMvc.perform(as(post("/projects/" + project + "/trash"), owner))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/projects/" + project), owner))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(jdbcClient
                .sql("select count(*) from document where project_id = :project")
                .param("project", project).query(Long.class).single()).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcClient
                .sql("select count(*) from episode_folders where project_id = :project")
                .param("project", project).query(Long.class).single()).isZero();
    }

    @Test
    void hidesOtherOwnersProjectsBehindNotFound() throws Exception {
        UUID project = createProject("남의 것");

        mockMvc.perform(as(get("/projects/" + project), stranger)).andExpect(status().isNotFound());
        mockMvc.perform(as(body(patch("/projects/" + project), "{\"name\":\"뺏기\"}"), stranger))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(post("/projects/" + project + "/trash"), stranger))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete("/projects/" + project), stranger)).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/projects"), stranger))
                .andExpect(jsonPath("$.projects.length()").value(0));
    }

    @Test
    void demandsTheIdentityHeaderTheGatewaySends() throws Exception {
        // 헤더 이름과 아래 거절 규칙은 authentication 서비스와 같다.
        org.assertj.core.api.Assertions.assertThat(CurrentUserArgumentResolver.HEADER)
                .isEqualTo("X-User-Id");

        mockMvc.perform(get("/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"))
                .andExpect(jsonPath("$.next_action").value("RELOGIN"));
    }

    @Test
    void refusesAnIdentityHeaderItCannotTrust() throws Exception {
        // 신원을 아예 못 읽는 것과 읽었는데 값이 틀린 것은 다르다. 후자는 잘못된 요청이다.
        mockMvc.perform(get("/projects").header(CurrentUserArgumentResolver.HEADER, "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // UUID.fromString이 받아들이는 비정규 표기도 거절한다.
        mockMvc.perform(get("/projects").header(CurrentUserArgumentResolver.HEADER, "1-2-3-4-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // 값이 둘이면 어느 것이 gateway의 것인지 알 수 없다. 고르지 않는다.
        mockMvc.perform(get("/projects")
                        .header(CurrentUserArgumentResolver.HEADER, owner.toString())
                        .header(CurrentUserArgumentResolver.HEADER, stranger.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsRequestBodiesItDoesNotRecognise() throws Exception {
        // 알 수 없는 필드를 조용히 버리면 클라이언트의 오타가 "저장됐는데 안 바뀐다"로 나타난다.
        mockMvc.perform(as(body(post("/projects"), "{\"name\":\"오타\",\"titel\":\"x\"}"), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(as(body(post("/projects"), "{\"name\":42}"), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(as(body(post("/projects"), "not json"), owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void treatsAnUnparsableIdentifierAsNotFound() throws Exception {
        mockMvc.perform(as(get("/projects/not-a-uuid"), owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }
}
