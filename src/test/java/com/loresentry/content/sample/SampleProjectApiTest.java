package com.loresentry.content.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.loresentry.content.document.DocumentService;
import com.loresentry.content.refresh.GraphRefreshRepository;
import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.JsonNode;

class SampleProjectApiTest extends ApiTestSupport {

    private static final String NAME = "별이 없는 세자";

    private static final String LAST_CHAPTER = "30화 · 동지 사흘 전";

    private static final String SEOHWA_DESCRIPTION = "별을 읽는 금지된 재능을 숨긴 관상감 서운녀";

    private static final int MANUSCRIPTS = 30;

    private static final int SETTINGS = 26;

    private static final int RELATIONS = 241;

    private static final List<String> NOT_REFRESHED = List.of(
            "28화 · 청에서 온 손님", "29화 · 분성(分星)의 값", LAST_CHAPTER);

    private static final Set<String> PICTURED = Set.of(
            "윤서화", "이현", "민도윤", "정시후", "허태정", "대비 심씨", "덕이",
            "흑요석 성반", "세자의 일기", "명부", "은하잠");

    private static final String ENGLISH_NAME = "The Glass Garden Records";

    private static final int ENGLISH_MANUSCRIPTS = 7;

    private static final int ENGLISH_SETTINGS = 14;

    private static final int ENGLISH_RELATIONS = 46;

    @MockitoSpyBean
    private DocumentService documents;

    @MockitoBean(name = "s3Client")
    private S3Client s3;

    @Autowired
    private GraphRefreshRepository refreshRuns;

    private Map<String, String> idsByTitle(String project) throws Exception {
        JsonNode tree = read(mockMvc.perform(as(get("/projects/" + project + "/files"))).andReturn());
        Map<String, String> ids = new HashMap<>();
        for (JsonNode document : tree.get("documents")) {
            ids.put(document.get("title").stringValue(), document.get("id").stringValue());
        }
        return ids;
    }

    private JsonNode createSample(UUID user) throws Exception {
        return createSample(post("/projects/sample"), user);
    }

    private JsonNode createSample(UUID user, String locale) throws Exception {
        return createSample(post("/projects/sample?locale={locale}", locale), user);
    }

    private JsonNode createSample(MockHttpServletRequestBuilder request, UUID user) throws Exception {
        MvcResult result = mockMvc.perform(as(request, user))
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
                .andExpect(jsonPath("$.last_file.title").value(LAST_CHAPTER))
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
                .andExpect(jsonPath("$.episodes.length()").value(5))
                .andExpect(jsonPath("$.episodes[0].name").value("1부. 서운녀"))
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
        Map<String, String> ids = idsByTitle(project);
        Map<String, String> titles = new HashMap<>();
        ids.forEach((title, id) -> titles.put(id, title));

        JsonNode content = read(mockMvc.perform(as(get("/files/" + ids.get("윤서화") + "/content")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.schema_version").value(1))
                .andExpect(jsonPath("$.body.doc.type").value("doc"))
                .andExpect(jsonPath("$.legacy_body_md").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.properties[0].key").value("description"))
                .andExpect(jsonPath("$.properties[0].value").value(SEOHWA_DESCRIPTION))
                .andExpect(jsonPath("$.revision_no").value(1))
                .andReturn());

        Set<String> blocks = new HashSet<>();
        content.get("body").get("doc").get("content").forEach(block -> blocks.add(block.get("type").stringValue()));
        assertThat(blocks).contains("heading", "bulletList", "paragraph");

        JsonNode chapter = read(mockMvc.perform(as(get("/files/" + ids.get(LAST_CHAPTER) + "/content")))
                .andExpect(status().isOk())
                .andReturn());
        chapter.get("body").get("doc").get("content")
                .forEach(block -> assertThat(block.get("type").stringValue()).isEqualTo("paragraph"));

        Set<String> related = new HashSet<>();
        for (JsonNode relation : content.get("relations")) {
            related.add(titles.get(relation.get("target_document_id").stringValue()));
            String key = relation.get("relation_key").stringValue();
            assertThat(key).startsWith("related_");
        }
        assertThat(related).hasSize(MANUSCRIPTS + 11)
                .contains("1화 · 별을 숨긴 아이", LAST_CHAPTER, "이현", "정시후", "덕이", "흑요석 성반");
        assertThat(content.get("relations").toString()).contains("소꿉친구");

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
                .andExpect(jsonPath("$.edges[*].description", everyItem(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyOrNullString()))))
                .andExpect(jsonPath("$.episodes.length()").value(5))
                .andExpect(jsonPath("$.episodes[*].document_ids.length()", everyItem(
                        org.hamcrest.Matchers.is(6))));
    }

    @Test
    void givesTheCharactersAndItemsTheirPictures() throws Exception {
        String project = createSample(owner).get("id").stringValue();

        JsonNode graph = read(mockMvc.perform(as(get("/projects/" + project + "/graph"))).andReturn());
        Map<String, String> pictures = new HashMap<>();
        for (JsonNode node : graph.get("nodes")) {
            if (!node.get("image_url").isNull()) {
                pictures.put(node.get("title").stringValue(), node.get("image_url").stringValue());
            }
        }

        assertThat(pictures).containsOnlyKeys(PICTURED);
        assertThat(pictures.values())
                .allMatch(url -> url.startsWith("https://media.test.invalid/projects/" + project + "/images/"))
                .allMatch(url -> url.endsWith(".webp"))
                .doesNotHaveDuplicates();
        verify(s3, times(PICTURED.size())).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(jdbcClient.sql("""
                        select count(*) from image
                        where project_id = :project and status = 'COMMITTED' and content_type = 'image/webp'
                        """)
                .param("project", UUID.fromString(project)).query(Long.class).single())
                .isEqualTo(PICTURED.size());
    }

    @Test
    void marksTheRulesTheSettingCheckMustKeep() throws Exception {
        String project = createSample(owner).get("id").stringValue();
        Map<String, String> ids = idsByTitle(project);

        JsonNode rules = read(mockMvc.perform(as(get("/files/" + ids.get("명성과 독성술") + "/content")))
                .andExpect(status().isOk())
                .andReturn());
        int core = 0;
        for (JsonNode block : rules.get("body").get("doc").get("content")) {
            if (block.path("attrs").path("core").booleanValue(false)) {
                assertThat(block.get("type").stringValue()).isEqualTo("paragraph");
                core++;
            }
        }
        assertThat(core).isEqualTo(3);
    }

    @Test
    void opensTheRecordedSuggestionsForReview() throws Exception {
        String project = createSample(owner).get("id").stringValue();
        UUID projectId = UUID.fromString(project);
        Map<String, String> ids = idsByTitle(project);

        Map<String, UUID> runs = new HashMap<>();
        jdbcClient.sql("select id, status, prompt_version from refresh_runs where project_id = :project")
                .param("project", projectId)
                .query((rows, n) -> runs.put(rows.getString("status") + " " + rows.getString("prompt_version"),
                        rows.getObject("id", UUID.class)))
                .list();
        assertThat(runs).containsOnlyKeys(
                "NO_CHANGE " + GraphRefreshRepository.SAMPLE_BASELINE,
                "READY " + GraphRefreshRepository.SAMPLE_REPLAY);
        UUID replay = runs.get("READY " + GraphRefreshRepository.SAMPLE_REPLAY);
        assertThat(jdbcClient.sql("select file_id from refresh_run_files where refresh_run_id = :run")
                .param("run", replay).query(UUID.class).list())
                .extracting(UUID::toString)
                .containsExactlyInAnyOrderElementsOf(NOT_REFRESHED.stream().map(ids::get).toList());
        assertThat(refreshRuns.changedFiles(projectId)).isEmpty();

        JsonNode detail = read(mockMvc.perform(as(get("/projects/" + project + "/graph-refresh/" + replay)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(detail.get("status").stringValue()).isEqualTo("READY");
        List<String> edited = new ArrayList<>();
        List<String> proposed = new ArrayList<>();
        for (JsonNode draft : detail.get("drafts")) {
            if ("NEW".equals(draft.get("kind").stringValue())) {
                proposed.add(draft.get("candidate").get("name").stringValue());
            } else {
                edited.add(draft.get("document").get("title").stringValue());
                assertThat(draft.get("matched").booleanValue()).isFalse();
            }
        }
        assertThat(edited).containsExactlyInAnyOrder(
                "28화 · 청에서 온 손님", "29화 · 분성(分星)의 값", LAST_CHAPTER,
                "이현", "민도윤", "윤서화", "정시후", "허태정", "세자의 일기", "흑요석 성반");
        assertThat(proposed).containsExactlyInAnyOrder(
                "진무량", "쌍성경(雙星鏡)", "남별궁", "흠천감", "분성(分星)의 법", "자시를 넘긴 밤");
        assertThat(jdbcClient.sql("select count(*) from document where refresh_lock_run_id = :run")
                .param("run", replay).query(Long.class).single()).isEqualTo(edited.size());

        mockMvc.perform(as(post("/projects/" + project + "/graph-refresh")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_REVIEW_PENDING"));
    }

    @Test
    void isSearchableLikeAnyOtherProject() throws Exception {
        String project = createSample(owner).get("id").stringValue();
        String query = URLEncoder.encode("남별궁", StandardCharsets.UTF_8);

        MvcResult result = mockMvc.perform(as(get(URI.create("/projects/" + project + "/search?q=" + query))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(read(result).toString()).contains(LAST_CHAPTER);
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
    void createsTheEnglishSampleWhenAskedForEnglish() throws Exception {
        JsonNode created = createSample(owner, "en");
        assertThat(created.get("name").stringValue()).isEqualTo(ENGLISH_NAME);
        assertThat(created.get("description").stringValue()).startsWith("The story of the people");
        assertThat(created.get("last_file").get("title").stringValue())
                .isEqualTo("Ch. 7 · Night of the Fracture");
        String project = created.get("id").stringValue();

        JsonNode tree = read(mockMvc.perform(as(get("/projects/" + project + "/files")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodes[*].name").value(contains(
                        "Episode 1. Season of Glass", "Episode 2. The North Door",
                        "Episode 3. Night of the Fracture")))
                .andExpect(jsonPath("$.documents.length()").value(ENGLISH_MANUSCRIPTS + ENGLISH_SETTINGS))
                .andReturn());
        String seoyun = null;
        Map<String, String> titles = new HashMap<>();
        for (JsonNode document : tree.get("documents")) {
            titles.put(document.get("id").stringValue(), document.get("title").stringValue());
            if ("Seoyun".equals(document.get("title").stringValue())) {
                seoyun = document.get("id").stringValue();
            }
        }

        JsonNode content = read(mockMvc.perform(as(get("/files/" + seoyun + "/content")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.doc.content[0].content[0].text").value(
                        org.hamcrest.Matchers.startsWith("Seoyun is the most recent recorder")))
                .andExpect(jsonPath("$.properties[0].value").value(
                        "The youngest recorder in the Garden Recorders"))
                .andReturn());

        // 키가 같으므로 관계도 한국어판과 같은 문서끼리 이어진다. 제목만 영어다.
        Set<String> related = new HashSet<>();
        for (JsonNode relation : content.get("relations")) {
            related.add(titles.get(relation.get("target_document_id").stringValue()));
        }
        assertThat(related).containsExactlyInAnyOrder(
                "Ch. 1 · The First Greenhouse", "Ch. 2 · Light Patrol", "Ch. 3 · The Cracked Lens",
                "Ch. 6 · The Glass Garden", "Ch. 7 · Night of the Fracture",
                "Garden Recorders", "Harin", "First Patrol");
        assertThat(content.get("relations").toString()).contains("Youngest recorder");

        mockMvc.perform(as(get("/projects/" + project + "/graph")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(ENGLISH_MANUSCRIPTS + ENGLISH_SETTINGS))
                .andExpect(jsonPath("$.edges.length()").value(ENGLISH_RELATIONS))
                .andExpect(jsonPath("$.episodes[0].document_ids.length()").value(3))
                .andExpect(jsonPath("$.episodes[1].document_ids.length()").value(2))
                .andExpect(jsonPath("$.episodes[2].document_ids.length()").value(2));

        assertThat(jdbcClient.sql("select count(*) from refresh_runs where project_id = :project")
                .param("project", UUID.fromString(project)).query(Long.class).single()).isZero();
        verifyNoInteractions(s3);
    }

    @Test
    void numbersTheEnglishNameOnItsOwn() throws Exception {
        // 한국어 예시와 이름이 다르므로 서로의 번호에 끼어들지 않는다.
        assertThat(createSample(owner).get("name").stringValue()).isEqualTo(NAME);
        assertThat(createSample(owner, "en").get("name").stringValue()).isEqualTo(ENGLISH_NAME);
        assertThat(createSample(owner, "en").get("name").stringValue()).isEqualTo(ENGLISH_NAME + " (2)");

        // 영어 이름에는 대소문자가 있다. 유일 인덱스처럼 대소문자를 가리지 않고 겹침을 본다.
        mockMvc.perform(as(body(post("/projects"), "{\"name\":\"the glass garden records (3)\"}")))
                .andExpect(status().isCreated());
        assertThat(createSample(owner, "en").get("name").stringValue()).isEqualTo(ENGLISH_NAME + " (4)");
    }

    @Test
    void fallsBackToKoreanForAnyOtherLocale() throws Exception {
        // 로케일은 소문자 ko 와 en 뿐이다. 그 밖의 값은 오류가 아니라 지금까지의 한국어 예시다.
        for (String locale : List.of("ko", "", "EN", "en-US", "fr")) {
            JsonNode created = createSample(UUID.randomUUID(), locale);
            assertThat(created.get("name").stringValue()).as(locale).isEqualTo(NAME);
            assertThat(created.get("last_file").get("title").stringValue()).as(locale)
                    .isEqualTo(LAST_CHAPTER);
        }
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
        assertThat(jdbcClient.sql("select count(*) from refresh_runs").query(Long.class).single()).isZero();
        assertThat(jdbcClient.sql("select count(*) from image").query(Long.class).single()).isZero();
        verify(s3, times(PICTURED.size())).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void needsTheCallersIdentity() throws Exception {
        mockMvc.perform(post("/projects/sample"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"));
    }

    @Test
    void loadsAConsistentSeed() {
        SampleSeed seed = SampleSeed.load(SampleSeed.KOREAN);

        assertThat(seed.episodes()).hasSize(5);
        assertThat(seed.episodes().stream().mapToInt(episode -> episode.manuscripts().size()).sum())
                .isEqualTo(MANUSCRIPTS);
        assertThat(seed.settings()).hasSize(SETTINGS);
        assertThat(seed.relations()).hasSize(RELATIONS);
        assertThat(seed.notRefreshed()).containsExactly("ch-28", "ch-29", "ch-30");
    }
}
