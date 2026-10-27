package com.loresentry.content.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class ChatApiTest extends ApiTestSupport {

    private UUID project;

    @BeforeEach
    void seed() throws Exception {
        project = createProject("{\"name\":\"유리 정원의 기록\",\"description\":\"빛을 적는 사람들\"}");
    }

    private record Works(UUID chapterOne, UUID chapterTwo, UUID chapterThree, UUID sideStory,
            UUID lena, UUID greenhouse, UUID world, UUID abandoned,
            UUID firstMemo, UUID lenaMemo, UUID secondMemo, long sizeBeforeTrash) {
    }

    private record Pair(String low, String high, String description) {

        static Pair of(UUID one, UUID other, String description) {
            String a = one.toString();
            String b = other.toString();
            return a.compareTo(b) < 0 ? new Pair(a, b, description) : new Pair(b, a, description);
        }
    }

    private Works seedWorks() throws Exception {
        UUID sideStory = document("MANUSCRIPT", "외전 · 🌙 달의 뒷면");
        UUID episodeOne = episode("1부");
        UUID episodeTwo = episode("2부");
        UUID chapterThree = chapter(episodeTwo, "3화 · 균열의 밤");
        UUID chapterTwo = chapter(episodeOne, "2화 · 빛의 순찰");
        UUID chapterOne = chapter(episodeOne, "1화 · 첫 번째 온실");
        mockMvc.perform(as(body(patch("/files/" + chapterOne + "/position"), json.writeValueAsString(Map.of(
                        "folder_code", "MANUSCRIPT",
                        "episode_id", episodeOne.toString(),
                        "before_file_id", chapterTwo.toString())))))
                .andExpect(status().isOk());

        UUID lena = document("CHARACTER", "레나 아르벨");
        UUID greenhouse = document("LOCATION", "유리 온실");
        UUID world = document("WORLDVIEW", "빛의 법칙");
        UUID abandoned = document("CHARACTER", "버려진 인물");

        save(lena, "레나 아르벨", List.of("레나는 🌱 정원사다.", "왼손으로 기록한다."),
                List.of(property("description", "처음 각성한 사람 ✨"), property("alias", ""),
                        property("alias", "레나"), property("alias", "아르벨")),
                Map.of());
        save(world, "빛의 법칙", List.of("빛은 유리를 기억한다."),
                List.of(property("description", "")), Map.of());
        save(greenhouse, "유리 온실", List.of(), List.of(), Map.of(lena, "레나가 일하는 곳"));
        save(chapterOne, "1화 · 첫 번째 온실", List.of("서윤이 문을 넘었다.", "👩‍👩‍👧 가족이 기다렸다."),
                List.of(property("description", "첫 회차 설명 🌙"), property("alias", ""),
                        property("alias", "온실 편")),
                Map.of(lena, "처음 각성한 곳 🔥"));
        save(chapterTwo, "2화 · 빛의 순찰", List.of("순찰"), List.of(), Map.of());
        save(chapterThree, "3화 · 균열의 밤", List.of("균열 🌙"), List.of(), Map.of());
        save(sideStory, "외전 · 🌙 달의 뒷면", List.of("달의 뒷면에서"), List.of(), Map.of());
        save(abandoned, "버려진 인물", List.of("사라질 본문"),
                List.of(property("description", "사라질 설명")), Map.of(lena, "사라질 관계"));

        UUID firstMemo = memo(Map.of("scope", "project", "title", "작업 메모", "body", "다음 화에서 🌙 회수"));
        UUID lenaMemo = memo(Map.of("scope", "file", "document_id", lena.toString(), "body", "레나는 왼손잡이"));
        UUID secondMemo = memo(Map.of("scope", "project", "body", "제목 없는 메모"));
        memo(Map.of("scope", "file", "document_id", abandoned.toString(), "title", "버린 메모",
                "body", "사라질 메모"));

        long sizeBeforeTrash = chatSize();
        mockMvc.perform(as(post("/files/" + abandoned + "/trash"))).andExpect(status().isNoContent());

        return new Works(chapterOne, chapterTwo, chapterThree, sideStory, lena, greenhouse, world, abandoned,
                firstMemo, lenaMemo, secondMemo, sizeBeforeTrash);
    }

    @Test
    void carriesTheWholeProjectInReadingOrder() throws Exception {
        Works works = seedWorks();

        MvcResult result = mockMvc.perform(as(get(snapshotPath())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.project.id").value(project.toString()))
                .andExpect(jsonPath("$.project.name").value("유리 정원의 기록"))
                .andExpect(jsonPath("$.project.description").value("빛을 적는 사람들"))
                .andReturn();
        JsonNode snapshot = read(result);

        assertThat(snapshot.propertyNames())
                .containsExactly("project", "manuscripts", "settings", "relations", "memos", "size");
        assertThat(snapshot.get("project").propertyNames()).containsExactly("id", "name", "description");

        JsonNode manuscripts = snapshot.get("manuscripts");
        assertThat(manuscripts.get(0).propertyNames()).containsExactly(
                "id", "title", "ordinal", "episode_name", "properties", "revision_no", "body_text",
                "updated_at");
        assertThat(texts(manuscripts, "id")).containsExactly(works.chapterOne().toString(),
                works.chapterTwo().toString(), works.chapterThree().toString(), works.sideStory().toString());
        assertThat(numbers(manuscripts, "ordinal")).containsExactly(1L, 2L, 3L, 4L);
        assertThat(texts(manuscripts, "episode_name")).containsExactly("1부", "1부", "2부", null);
        assertThat(texts(manuscripts, "title")).containsExactly(
                "1화 · 첫 번째 온실", "2화 · 빛의 순찰", "3화 · 균열의 밤", "외전 · 🌙 달의 뒷면");
        assertThat(numbers(manuscripts, "revision_no")).containsOnly(1L);
        assertThat(manuscripts.get(0).get("body_text").stringValue())
                .isEqualTo("서윤이 문을 넘었다.\n👩‍👩‍👧 가족이 기다렸다.");
        assertThat(manuscripts.get(0).get("updated_at").isString()).isTrue();
        JsonNode chapterOneProperties = manuscripts.get(0).get("properties");
        assertThat(texts(chapterOneProperties, "key")).containsExactly("description", "alias");
        assertThat(texts(chapterOneProperties, "value")).containsExactly("첫 회차 설명 🌙", "온실 편");
        for (int i = 1; i < manuscripts.size(); i++) {
            assertThat(manuscripts.get(i).get("properties").size()).isZero();
        }

        JsonNode settings = snapshot.get("settings");
        assertThat(settings.get(0).propertyNames()).containsExactly(
                "id", "folder_code", "title", "properties", "revision_no", "body_text", "updated_at");
        assertThat(texts(settings, "id")).containsExactly(
                works.world().toString(), works.lena().toString(), works.greenhouse().toString());
        assertThat(texts(settings, "folder_code")).containsExactly("WORLDVIEW", "CHARACTER", "LOCATION");
        assertThat(settings.get(0).get("properties").size()).isZero();
        JsonNode lenaProperties = settings.get(1).get("properties");
        assertThat(texts(lenaProperties, "key")).containsExactly("description", "alias", "alias");
        assertThat(texts(lenaProperties, "value")).containsExactly("처음 각성한 사람 ✨", "레나", "아르벨");
        assertThat(settings.get(1).get("body_text").stringValue()).isEqualTo("레나는 🌱 정원사다.\n왼손으로 기록한다.");
        assertThat(settings.get(2).get("body_text").stringValue()).isEmpty();

        JsonNode relations = snapshot.get("relations");
        assertThat(relations.get(0).propertyNames())
                .containsExactly("low_document_id", "high_document_id", "description");
        List<Pair> expectedRelations = Stream.of(
                        Pair.of(works.chapterOne(), works.lena(), "처음 각성한 곳 🔥"),
                        Pair.of(works.greenhouse(), works.lena(), "레나가 일하는 곳"))
                .sorted(Comparator.comparing(Pair::low).thenComparing(Pair::high))
                .toList();
        List<Pair> actualRelations = new ArrayList<>();
        for (JsonNode relation : relations) {
            actualRelations.add(new Pair(relation.get("low_document_id").stringValue(),
                    relation.get("high_document_id").stringValue(), relation.get("description").stringValue()));
        }
        assertThat(actualRelations).containsExactlyElementsOf(expectedRelations);

        JsonNode memos = snapshot.get("memos");
        assertThat(memos.get(0).propertyNames())
                .containsExactly("id", "scope", "document_id", "title", "body", "updated_at");
        assertThat(texts(memos, "id")).containsExactly(works.firstMemo().toString(),
                works.lenaMemo().toString(), works.secondMemo().toString());
        assertThat(texts(memos, "scope")).containsExactly("PROJECT", "FILE", "PROJECT");
        assertThat(texts(memos, "document_id")).containsExactly(null, works.lena().toString(), null);
        assertThat(texts(memos, "title")).containsExactly("작업 메모", null, null);
        assertThat(texts(memos, "body")).containsExactly("다음 화에서 🌙 회수", "레나는 왼손잡이", "제목 없는 메모");
    }

    @Test
    void countsCodePointsTheSameWayInTheSnapshotAndTheAggregate() throws Exception {
        Works works = seedWorks();

        List<String> included = List.of(
                "1화 · 첫 번째 온실", "서윤이 문을 넘었다.\n👩‍👩‍👧 가족이 기다렸다.", "첫 회차 설명 🌙", "온실 편",
                "2화 · 빛의 순찰", "순찰",
                "3화 · 균열의 밤", "균열 🌙",
                "외전 · 🌙 달의 뒷면", "달의 뒷면에서",
                "빛의 법칙", "빛은 유리를 기억한다.",
                "레나 아르벨", "레나는 🌱 정원사다.\n왼손으로 기록한다.", "처음 각성한 사람 ✨", "레나", "아르벨",
                "유리 온실", "",
                "처음 각성한 곳 🔥", "레나가 일하는 곳",
                "작업 메모", "다음 화에서 🌙 회수", "레나는 왼손잡이", "제목 없는 메모");
        long expected = codePoints(included);
        assertThat(expected).isLessThan(included.stream().mapToLong(String::length).sum());

        mockMvc.perform(as(get(snapshotPath())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(expected));
        mockMvc.perform(as(get(sizePath())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.size").value(expected));
        assertThat(chatSize()).isEqualTo(snapshotSize());

        List<String> trashed = List.of("버려진 인물", "사라질 본문", "사라질 설명", "사라질 관계", "버린 메모", "사라질 메모");
        assertThat(works.sizeBeforeTrash() - expected).isEqualTo(codePoints(trashed));
    }

    @Test
    void answersTheSameBytesForTheSameData() throws Exception {
        seedWorks();

        String first = mockMvc.perform(as(get(snapshotPath()))).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(as(get(snapshotPath()))).andReturn().getResponse().getContentAsString();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void answersAnEmptyProjectWithEmptyListsAndZeroSize() throws Exception {
        UUID empty = createProject("{\"name\":\"빈 작품\"}");

        mockMvc.perform(as(get("/internal/projects/" + empty + "/chat-snapshot")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.project.name").value("빈 작품"))
                .andExpect(jsonPath("$.project.description").value(""))
                .andExpect(jsonPath("$.manuscripts.length()").value(0))
                .andExpect(jsonPath("$.settings.length()").value(0))
                .andExpect(jsonPath("$.relations.length()").value(0))
                .andExpect(jsonPath("$.memos.length()").value(0))
                .andExpect(jsonPath("$.size").value(0));
        mockMvc.perform(as(get("/internal/projects/" + empty + "/chat-size")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(0));
    }

    @Test
    void refusesWhatTheCallerCannotOpen() throws Exception {
        for (String path : List.of(snapshotPath(), sizePath())) {
            mockMvc.perform(as(get(path), stranger))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"));
        }
        for (String suffix : List.of("chat-snapshot", "chat-size")) {
            mockMvc.perform(as(get("/internal/projects/not-a-uuid/" + suffix)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
            mockMvc.perform(as(get("/internal/projects/" + UUID.randomUUID() + "/" + suffix)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        }

        mockMvc.perform(as(post("/projects/" + project + "/trash"))).andExpect(status().isNoContent());
        for (String path : List.of(snapshotPath(), sizePath())) {
            mockMvc.perform(as(get(path)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        }
    }

    @Test
    void mapsTheProjectWithoutBodiesInSnapshotOrder() throws Exception {
        seedWorks();

        JsonNode snapshot = read(mockMvc.perform(as(get(snapshotPath()))).andReturn());
        MvcResult result = mockMvc.perform(as(get(mapPath())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        JsonNode map = read(result);

        assertThat(map.propertyNames()).containsExactly("project", "manuscripts", "settings", "relations", "memos");
        JsonNode manuscripts = map.get("manuscripts");
        assertThat(manuscripts.get(0).propertyNames()).containsExactly(
                "id", "title", "ordinal", "episode_name", "properties", "revision_no", "chars", "updated_at");
        assertThat(texts(manuscripts, "id")).containsExactlyElementsOf(texts(snapshot.get("manuscripts"), "id"));
        assertThat(numbers(manuscripts, "ordinal")).containsExactly(1L, 2L, 3L, 4L);
        assertThat(map.get("settings").get(0).propertyNames()).containsExactly(
                "id", "folder_code", "title", "properties", "revision_no", "chars", "updated_at");
        assertThat(texts(map.get("settings"), "id")).containsExactlyElementsOf(texts(snapshot.get("settings"), "id"));
        assertThat(map.get("memos").get(0).propertyNames())
                .containsExactly("id", "scope", "document_id", "title", "chars", "updated_at");
        assertThat(texts(map.get("memos"), "id")).containsExactlyElementsOf(texts(snapshot.get("memos"), "id"));
        assertThat(map.get("relations")).isEqualTo(snapshot.get("relations"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("서윤이 문을 넘었다");

        assertThat(manuscripts.get(0).get("chars").longValue()).isEqualTo(codePoints(List.of(
                "1화 · 첫 번째 온실", "서윤이 문을 넘었다.\n👩‍👩‍👧 가족이 기다렸다.", "첫 회차 설명 🌙", "온실 편")));
        assertThat(map.get("settings").get(1).get("chars").longValue()).isEqualTo(codePoints(List.of(
                "레나 아르벨", "레나는 🌱 정원사다.\n왼손으로 기록한다.", "처음 각성한 사람 ✨", "레나", "아르벨")));
        assertThat(map.get("memos").get(1).get("chars").longValue()).isEqualTo(codePoints(List.of("레나는 왼손잡이")));

        long documentChars = 0;
        for (JsonNode node : manuscripts) {
            documentChars += node.get("chars").longValue();
        }
        for (JsonNode node : map.get("settings")) {
            documentChars += node.get("chars").longValue();
        }
        for (JsonNode node : map.get("memos")) {
            documentChars += node.get("chars").longValue();
        }
        long relationChars = codePoints(List.of("처음 각성한 곳 🔥", "레나가 일하는 곳"));
        assertThat(documentChars + relationChars).isEqualTo(chatSize());
    }

    @Test
    void readsChosenDocumentsWithProjectWideOrdinals() throws Exception {
        Works works = seedWorks();
        JsonNode snapshot = read(mockMvc.perform(as(get(snapshotPath()))).andReturn());
        UUID unknown = UUID.randomUUID();

        JsonNode documents = read(mockMvc.perform(as(body(post(documentsPath()), json.writeValueAsString(Map.of(
                        "document_ids", List.of(works.chapterThree().toString(), works.lena().toString(),
                                works.abandoned().toString(), unknown.toString(), works.lena().toString()),
                        "memo_ids", List.of(works.lenaMemo().toString()))))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn());

        assertThat(documents.propertyNames()).containsExactly("manuscripts", "settings", "memos", "missing");
        assertThat(documents.get("manuscripts").size()).isEqualTo(1);
        assertThat(documents.get("manuscripts").get(0)).isEqualTo(snapshot.get("manuscripts").get(2));
        assertThat(documents.get("settings").size()).isEqualTo(1);
        ObjectNode lena = (ObjectNode) documents.get("settings").get(0);
        assertThat(lena.remove("core_facts")).isEqualTo(json.createArrayNode());
        assertThat(lena).isEqualTo(snapshot.get("settings").get(1));
        assertThat(documents.get("memos").size()).isEqualTo(1);
        assertThat(documents.get("memos").get(0)).isEqualTo(snapshot.get("memos").get(1));
        assertThat(texts(documents.get("missing"), null))
                .containsExactly(works.abandoned().toString(), unknown.toString());
    }

    @Test
    void carriesTheCoreFactsMarkedInEachSettingDocument() throws Exception {
        UUID chapter = document("MANUSCRIPT", "12화 · 균열의 밤");
        UUID greenhouse = document("LOCATION", "북쪽 온실");
        UUID harin = document("CHARACTER", "하린");
        UUID legacy = document("WORLDVIEW", "옛 설정");
        saveBlocks(greenhouse, "북쪽 온실", List.of(
                Map.of("type", "heading", "attrs", Map.of("level", 2, "core", true),
                        "content", List.of(text("출입 규칙"))),
                Map.of("type", "paragraph", "attrs", Map.of("core", true), "content", List.of(
                        text("북쪽 문은 "),
                        Map.of("type", "text", "marks", List.of(Map.of("type", "bold")), "text", "낡은 열쇠"),
                        text(" 없이는 열리지 않는다."))),
                Map.of("type", "paragraph", "content", List.of(text("유리 지붕은 밤마다 빛난다."))),
                Map.of("type", "paragraph", "attrs", Map.of("core", true), "content", List.of(text("  "))),
                Map.of("type", "paragraph", "attrs", Map.of("core", true), "content", List.of(
                        text("온실지기는 하린이다."), Map.of("type", "hardBreak"), text("밤에만 들어간다.")))),
                List.of(), Map.of());
        save(harin, "하린", List.of("하린은 왼손잡이다."), List.of(), Map.of());
        saveBlocks(chapter, "12화 · 균열의 밤", List.of(
                        Map.of("type", "paragraph", "attrs", Map.of("core", true),
                                "content", List.of(text("서윤은 천천히 문을 밀었다.")))),
                List.of(), Map.of());
        jdbcClient.sql("update document set body_json = null, body_md = :md where id = :id")
                .param("md", "옛 본문").param("id", legacy).update();

        JsonNode documents = read(mockMvc.perform(as(body(post(documentsPath()), json.writeValueAsString(Map.of(
                        "document_ids", List.of(chapter.toString(), greenhouse.toString(), harin.toString(),
                                legacy.toString()))))))
                .andExpect(status().isOk())
                .andReturn());

        JsonNode settings = documents.get("settings");
        assertThat(texts(settings, "id"))
                .containsExactly(legacy.toString(), harin.toString(), greenhouse.toString());
        for (JsonNode setting : settings) {
            assertThat(setting.propertyNames()).containsExactly(
                    "id", "folder_code", "title", "properties", "revision_no", "body_text", "core_facts",
                    "updated_at");
        }
        assertThat(settings.get(0).get("core_facts")).isEqualTo(json.createArrayNode());
        assertThat(settings.get(1).get("core_facts")).isEqualTo(json.createArrayNode());
        assertThat(texts(settings.get(2).get("core_facts"), null)).containsExactly(
                "출입 규칙", "북쪽 문은 낡은 열쇠 없이는 열리지 않는다.", "온실지기는 하린이다.\n밤에만 들어간다.");
        assertThat(documents.get("manuscripts").get(0).propertyNames()).containsExactly(
                "id", "title", "ordinal", "episode_name", "properties", "revision_no", "body_text",
                "updated_at");
    }

    @Test
    void limitsAndGuardsDocumentReads() throws Exception {
        Works works = seedWorks();
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= ChatService.DOCUMENTS_LIMIT; i++) {
            tooMany.add(UUID.randomUUID().toString());
        }
        mockMvc.perform(as(body(post(documentsPath()), json.writeValueAsString(Map.of("document_ids", tooMany)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(as(body(post(documentsPath()), json.writeValueAsString(Map.of(
                        "document_ids", List.of(works.lena().toString())))), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mockMvc.perform(as(get(mapPath()), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mockMvc.perform(as(body(post(documentsPath()), "{}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manuscripts.length()").value(0))
                .andExpect(jsonPath("$.missing.length()").value(0));

        UUID other = createProject("{\"name\":\"다른 작품\"}");
        mockMvc.perform(as(body(post("/internal/projects/" + other + "/chat-documents"),
                        json.writeValueAsString(Map.of("document_ids", List.of(works.lena().toString()))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.length()").value(0))
                .andExpect(jsonPath("$.missing[0]").value(works.lena().toString()));
    }

    private String mapPath() {
        return "/internal/projects/" + project + "/chat-map";
    }

    private String documentsPath() {
        return "/internal/projects/" + project + "/chat-documents";
    }

    private String snapshotPath() {
        return "/internal/projects/" + project + "/chat-snapshot";
    }

    private String sizePath() {
        return "/internal/projects/" + project + "/chat-size";
    }

    private long chatSize() throws Exception {
        return read(mockMvc.perform(as(get(sizePath()))).andExpect(status().isOk()).andReturn())
                .get("size").longValue();
    }

    private long snapshotSize() throws Exception {
        return read(mockMvc.perform(as(get(snapshotPath()))).andExpect(status().isOk()).andReturn())
                .get("size").longValue();
    }

    private UUID createProject(String payload) throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), payload)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(created).get("id").stringValue());
    }

    private UUID createFile(Map<String, Object> request) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        json.writeValueAsString(request))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private UUID episode(String name) throws Exception {
        return createFile(Map.of("kind", "episode", "title", name));
    }

    private UUID document(String folderCode, String title) throws Exception {
        return createFile(Map.of("kind", "document", "folder_code", folderCode, "title", title));
    }

    private UUID chapter(UUID episode, String title) throws Exception {
        return createFile(Map.of("kind", "document", "folder_code", "MANUSCRIPT", "title", title,
                "episode_id", episode.toString()));
    }

    private void save(UUID document, String title, List<String> paragraphs,
            List<Map<String, String>> properties, Map<UUID, String> relations) throws Exception {
        List<Map<String, Object>> blocks = paragraphs.stream()
                .map(text -> Map.<String, Object>of("type", "paragraph",
                        "content", List.of(Map.of("type", "text", "text", text))))
                .toList();
        saveBlocks(document, title, blocks, properties, relations);
    }

    private void saveBlocks(UUID document, String title, List<Map<String, Object>> blocks,
            List<Map<String, String>> properties, Map<UUID, String> relations) throws Exception {
        List<Map<String, String>> relationList = relations.entrySet().stream()
                .map(entry -> Map.of("target_document_id", entry.getKey().toString(),
                        "description", entry.getValue()))
                .toList();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", title);
        snapshot.put("body", Map.of("schema_version", 1, "doc", Map.of("type", "doc", "content", blocks)));
        snapshot.put("properties", properties);
        snapshot.put("relations", relationList);
        mockMvc.perform(as(body(put("/files/" + document + "/content"), json.writeValueAsString(snapshot)))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
    }

    private static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, String> property(String key, String value) {
        return Map.of("key", key, "value", value);
    }

    private UUID memo(Map<String, Object> request) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/memos"),
                        json.writeValueAsString(request))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode element : array) {
            JsonNode value = field == null ? element : element.get(field);
            values.add(value.isNull() ? null : value.stringValue());
        }
        return values;
    }

    private static List<Long> numbers(JsonNode array, String field) {
        List<Long> values = new ArrayList<>();
        for (JsonNode element : array) {
            values.add(element.get(field).longValue());
        }
        return values;
    }

    private static long codePoints(List<String> texts) {
        return texts.stream().mapToLong(text -> text.codePointCount(0, text.length())).sum();
    }
}
