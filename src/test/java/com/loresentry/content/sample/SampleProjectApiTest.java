package com.loresentry.content.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.loresentry.content.document.DocumentService;
import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

class SampleProjectApiTest extends ApiTestSupport {

    private static final String NAME = "유리 정원의 기록";

    private static final int MANUSCRIPTS = 7;

    private static final int SETTINGS = 14;

    private static final int RELATIONS = 46;

    @MockitoSpyBean
    private DocumentService documents;

    private JsonNode createSample(UUID user) throws Exception {
        MvcResult result = mockMvc.perform(as(post("/projects/sample"), user))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = read(result);
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/projects/" + body.get("id").stringValue());
        return body;
    }

    @Test
    void answersLikeProjectCreation() throws Exception {
        MvcResult result = mockMvc.perform(as(post("/projects/sample")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value(NAME))
                .andExpect(jsonPath("$.description").isNotEmpty())
                .andExpect(jsonPath("$.trashed_at").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.last_worked_at").exists())
                // 원고를 마지막으로 저장하므로 예시를 열면 마지막 회차가 보인다.
                .andExpect(jsonPath("$.last_file.title").value("7화 · 균열의 밤"))
                .andReturn();
        String id = read(result).get("id").stringValue();

        mockMvc.perform(as(get("/projects")))
                .andExpect(jsonPath("$.projects.length()").value(1))
                .andExpect(jsonPath("$.projects[0].id").value(id));
    }

    @Test
    void fillsTheTreeWithEpisodesManuscriptsAndSettings() throws Exception {
        String project = createSample(owner).get("id").stringValue();

        MvcResult tree = mockMvc.perform(as(get("/projects/" + project + "/files")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodes.length()").value(3))
                .andExpect(jsonPath("$.episodes[0].name").value("Episode 1. 유리의 계절"))
                .andExpect(jsonPath("$.documents.length()").value(MANUSCRIPTS + SETTINGS))
                .andExpect(jsonPath("$.documents[*].revision_no", everyItem(
                        org.hamcrest.Matchers.is(1))))
                .andReturn();

        Map<String, Integer> byFolder = new HashMap<>();
        for (JsonNode document : read(tree).get("documents")) {
            byFolder.merge(document.get("folder_code").stringValue(), 1, Integer::sum);
            boolean manuscript = "MANUSCRIPT".equals(document.get("folder_code").stringValue());
            assertThat(document.get("episode_id").isNull()).isEqualTo(!manuscript);
            assertThat(document.get("char_count").intValue()).isPositive();
        }
        assertThat(byFolder).containsOnlyKeys(
                "MANUSCRIPT", "CHARACTER", "LOCATION", "ORGANIZATION", "ITEM", "EVENT", "WORLDVIEW");
        assertThat(byFolder.get("MANUSCRIPT")).isEqualTo(MANUSCRIPTS);
    }

    @Test
    void storesBodiesAndRelationsTheWayTheEditorSavesThem() throws Exception {
        String project = createSample(owner).get("id").stringValue();
        JsonNode tree = read(mockMvc.perform(as(get("/projects/" + project + "/files"))).andReturn());
        String seoyun = null;
        Map<String, String> titles = new HashMap<>();
        for (JsonNode document : tree.get("documents")) {
            titles.put(document.get("id").stringValue(), document.get("title").stringValue());
            if ("서윤".equals(document.get("title").stringValue())) {
                seoyun = document.get("id").stringValue();
            }
        }

        JsonNode content = read(mockMvc.perform(as(get("/files/" + seoyun + "/content")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.schema_version").value(1))
                .andExpect(jsonPath("$.body.doc.type").value("doc"))
                .andExpect(jsonPath("$.body.doc.content[0].type").value("paragraph"))
                .andExpect(jsonPath("$.body.doc.content[0].content[0].text").value(
                        org.hamcrest.Matchers.startsWith("서윤은 정원 기록단에")))
                .andExpect(jsonPath("$.legacy_body_md").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.properties[0].key").value("description"))
                .andExpect(jsonPath("$.properties[0].value").value("정원 기록단의 막내 기록관"))
                .andExpect(jsonPath("$.revision_no").value(1))
                .andReturn());

        // 서윤이 먼저 저장되고 원고가 나중에 가리켰어도, 서윤 쪽에 원고 관계가 모두 보인다.
        Set<String> related = new HashSet<>();
        for (JsonNode relation : content.get("relations")) {
            related.add(titles.get(relation.get("target_document_id").stringValue()));
            String key = relation.get("relation_key").stringValue();
            assertThat(key).startsWith("related_");
        }
        assertThat(related).containsExactlyInAnyOrder(
                "1화 · 첫 번째 온실", "2화 · 빛의 순찰", "3화 · 금 간 렌즈", "6화 · 유리 정원", "7화 · 균열의 밤",
                "정원 기록단", "하린", "첫 순찰");
        assertThat(content.get("relations").toString()).contains("막내 기록관");

        // 관계에는 방향이 없고 한 쌍은 한 행이다(V10). 양쪽에서 보이는 것은 행이 둘이어서가 아니다.
        long total = jdbcClient.sql("""
                        select count(*) from document_relations r
                        join document d on d.id = r.low_document_id where d.project_id = :project
                        """)
                .param("project", UUID.fromString(project)).query(Long.class).single();
        assertThat(total).isEqualTo(RELATIONS);
        long foreign = jdbcClient.sql("""
                        select count(*) from document_relations r
                        join document low on low.id = r.low_document_id
                        join document high on high.id = r.high_document_id
                        where low.project_id = :project and high.project_id <> low.project_id
                        """)
                .param("project", UUID.fromString(project)).query(Long.class).single();
        assertThat(foreign).isZero();
    }

    @Test
    void givesTheGraphAndTimelineSomethingToDraw() throws Exception {
        String project = createSample(owner).get("id").stringValue();

        mockMvc.perform(as(get("/projects/" + project + "/graph")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(MANUSCRIPTS + SETTINGS))
                .andExpect(jsonPath("$.nodes[*].description", everyItem(notNullValue())))
                .andExpect(jsonPath("$.edges.length()").value(RELATIONS))
                .andExpect(jsonPath("$.episodes.length()").value(3))
                .andExpect(jsonPath("$.episodes[0].document_ids.length()").value(3))
                .andExpect(jsonPath("$.episodes[1].document_ids.length()").value(2))
                .andExpect(jsonPath("$.episodes[2].document_ids.length()").value(2));
    }

    @Test
    void isSearchableLikeAnyOtherProject() throws Exception {
        String project = createSample(owner).get("id").stringValue();
        String query = URLEncoder.encode("발자국", StandardCharsets.UTF_8);

        MvcResult result = mockMvc.perform(as(get(URI.create("/projects/" + project + "/search?q=" + query))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(read(result).toString()).contains("7화 · 균열의 밤");
    }

    @Test
    void numbersTheNameWhenTheOwnerAlreadyHasOne() throws Exception {
        assertThat(createSample(owner).get("name").stringValue()).isEqualTo(NAME);
        assertThat(createSample(owner).get("name").stringValue()).isEqualTo(NAME + " (2)");
        JsonNode third = createSample(owner);
        assertThat(third.get("name").stringValue()).isEqualTo(NAME + " (3)");

        // 휴지통의 이름은 비어 있는 것으로 본다. 유일 인덱스와 같은 규칙이다.
        mockMvc.perform(as(post("/projects/" + third.get("id").stringValue() + "/trash")))
                .andExpect(status().isNoContent());
        assertThat(createSample(owner).get("name").stringValue()).isEqualTo(NAME + " (3)");

        // 다른 사용자의 이름과는 겹쳐도 된다.
        assertThat(createSample(stranger).get("name").stringValue()).isEqualTo(NAME);
    }

    @Test
    void leavesNothingBehindWhenPopulatingFails() throws Exception {
        AtomicInteger saves = new AtomicInteger();
        willAnswer(invocation -> {
            if (saves.incrementAndGet() == 5) {
                throw new IllegalStateException("boom");
            }
            return invocation.callRealMethod();
        }).given(documents).save(any(), any(), anyLong(), any(), any());

        mockMvc.perform(as(post("/projects/sample")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        assertThat(jdbcClient.sql("select count(*) from projects").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from document").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from episode_folders").query(Long.class).single()).isZero();
    }

    @Test
    void needsTheCallersIdentity() throws Exception {
        mockMvc.perform(post("/projects/sample"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"));
    }

    @Test
    void loadsAConsistentSeed() {
        SampleSeed seed = SampleSeed.load();

        assertThat(seed.episodes()).hasSize(3);
        assertThat(seed.episodes().stream().mapToInt(episode -> episode.manuscripts().size()).sum())
                .isEqualTo(MANUSCRIPTS);
        assertThat(seed.settings()).hasSize(SETTINGS);
        assertThat(seed.relations()).hasSize(RELATIONS);
    }
}
