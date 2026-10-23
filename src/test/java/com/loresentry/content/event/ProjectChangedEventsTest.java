package com.loresentry.content.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/**
 * 동작마다 content.project.changed.v1 로 어떤 이벤트가 몇 개 나가는지 API 를 통해 확인한다. 규칙의 원본은
 * {@code docs/events/content-project-changed-v1.md} 4절이다. outbox_events 는 테스트 사이에 지워지지 않으므로
 * 언제나 이 테스트가 만든 문서·프로젝트 id 로 거른다.
 */
class ProjectChangedEventsTest extends ApiTestSupport {

    private static final String ALL_FIELDS = "[\"TITLE\",\"BODY\",\"PROPERTIES\",\"FOLDER\",\"STATE\",\"RELATIONS\"]";

    private UUID project;

    record Event(String type, String key, JsonNode payload) {
    }

    @BeforeEach
    void createProject() throws Exception {
        project = idOf(mockMvc.perform(as(body(post("/projects"), "{\"name\":\"이벤트 확인\"}")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void creatingADocumentPublishesItsWholeSnapshot() throws Exception {
        UUID file = createDocument("CHARACTER", "유중혁", null);

        List<Event> events = eventsOf(file);
        assertThat(events).hasSize(1);
        Event created = events.getFirst();
        assertThat(created.type()).isEqualTo("FileChanged");
        assertThat(created.key()).isEqualTo(project.toString());
        assertThat(created.payload()).isEqualTo(json.readTree("""
                {"project_id":"%s","file_id":"%s","change_type":"CREATED",
                 "changed_fields":%s,
                 "file":{"title":"유중혁","folder_code":"CHARACTER","episode_id":null,"state":"ACTIVE",
                         "revision_no":0,"body_text":"","properties":[]},
                 "relations":[]}
                """.formatted(project, file, ALL_FIELDS)));
    }

    @Test
    void renamingPublishesTheTitle() throws Exception {
        UUID file = createDocument("CHARACTER", "유중혁", null);

        mockMvc.perform(as(body(patch("/files/" + file), "{\"title\":\"김독자\"}"))).andExpect(status().isOk());

        JsonNode renamed = lastOf(file).payload();
        assertThat(renamed.get("change_type").asString()).isEqualTo("UPDATED");
        assertThat(renamed.get("changed_fields")).isEqualTo(json.readTree("[\"TITLE\"]"));
        assertThat(renamed.at("/file/title").asString()).isEqualTo("김독자");
        // 이름 변경은 저장이 아니라 revision 이 그대로다.
        assertThat(renamed.at("/file/revision_no").asLong()).isZero();
    }

    /** 순서(rank)는 싣지 않으므로, 같은 자리 안에서 순서만 바꾼 이동은 스냅샷을 바꾸지 않는다. */
    @Test
    void movingPublishesOnlyWhenTheFolderChanges() throws Exception {
        UUID first = createDocument("CHARACTER", "유중혁", null);
        UUID second = createDocument("CHARACTER", "김독자", null);

        move(second, "{\"folder_code\":\"CHARACTER\",\"before_file_id\":\"" + first + "\"}");
        assertThat(eventsOf(second)).hasSize(1);

        move(second, "{\"folder_code\":\"LOCATION\"}");
        JsonNode moved = lastOf(second).payload();
        assertThat(moved.get("changed_fields")).isEqualTo(json.readTree("[\"FOLDER\"]"));
        assertThat(moved.at("/file/folder_code").asString()).isEqualTo("LOCATION");
    }

    @Test
    void deletingAnEpisodeMovesItsDocumentsOut() throws Exception {
        UUID episode = idOf(mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"episode\",\"title\":\"1부\"}")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        UUID chapter = createDocument("MANUSCRIPT", "제1화", episode);
        assertThat(lastOf(chapter).payload().at("/file/episode_id").asString()).isEqualTo(episode.toString());

        mockMvc.perform(as(delete("/episodes/" + episode))).andExpect(status().isNoContent());

        JsonNode moved = lastOf(chapter).payload();
        assertThat(moved.get("change_type").asString()).isEqualTo("UPDATED");
        assertThat(moved.get("changed_fields")).isEqualTo(json.readTree("[\"FOLDER\"]"));
        assertThat(moved.at("/file/episode_id").isNull()).isTrue();
    }

    /** 휴지통은 표시일 뿐이라 스냅샷 전체를 그대로 싣고 상태만 바꾼다. 다시 보내거나 다시 되살리면 아무것도 없다. */
    @Test
    void trashAndRestoreFlipTheStateOnly() throws Exception {
        UUID file = createDocument("CHARACTER", "유중혁", null);

        mockMvc.perform(as(post("/files/" + file + "/trash"))).andExpect(status().isNoContent());
        JsonNode trashed = lastOf(file).payload();
        assertThat(trashed.get("change_type").asString()).isEqualTo("TRASHED");
        assertThat(trashed.get("changed_fields")).isEqualTo(json.readTree("[\"STATE\"]"));
        assertThat(trashed.at("/file/state").asString()).isEqualTo("TRASHED");
        assertThat(trashed.at("/file/title").asString()).isEqualTo("유중혁");
        assertThat(trashed.get("relations").isArray()).isTrue();

        mockMvc.perform(as(post("/files/" + file + "/trash"))).andExpect(status().isNoContent());
        assertThat(eventsOf(file)).hasSize(2);

        mockMvc.perform(as(post("/files/" + file + "/restore"))).andExpect(status().isOk());
        JsonNode restored = lastOf(file).payload();
        assertThat(restored.get("change_type").asString()).isEqualTo("RESTORED");
        assertThat(restored.get("changed_fields")).isEqualTo(json.readTree("[\"STATE\"]"));
        assertThat(restored.at("/file/state").asString()).isEqualTo("ACTIVE");

        mockMvc.perform(as(post("/files/" + file + "/restore"))).andExpect(status().isOk());
        assertThat(eventsOf(file)).hasSize(3);
    }

    /** changed_fields 는 저장 SQL 이 실제로 바꾼 것이다. 아무것도 안 바뀐 저장은 이벤트를 내지 않는다. */
    @Test
    void savingPublishesWhatTheSaveActuallyChanged() throws Exception {
        UUID file = createDocument("CHARACTER", "유중혁", null);
        String described = "[{\"key\":\"description\",\"value\":\"회귀자\"}]";

        save(file, "유중혁", "회귀를 반복한다.", described, "[]");
        JsonNode first = lastOf(file).payload();
        assertThat(first.get("changed_fields")).isEqualTo(json.readTree("[\"BODY\",\"PROPERTIES\"]"));
        assertThat(first.at("/file/body_text").asString()).isEqualTo("회귀를 반복한다.");
        assertThat(first.at("/file/properties")).isEqualTo(json.readTree(described));
        // revision 은 최상위가 아니라 스냅샷(file)에 있고, 저장할 때마다 오른다.
        assertThat(first.has("revision_no")).isFalse();
        assertThat(first.at("/file/revision_no").asLong()).isEqualTo(1);

        save(file, "유중혁(3회차)", "회귀를 반복한다.", described, "[]");
        JsonNode second = lastOf(file).payload();
        assertThat(second.get("changed_fields")).isEqualTo(json.readTree("[\"TITLE\"]"));
        assertThat(second.at("/file/revision_no").asLong()).isEqualTo(2);

        int before = eventsOf(file).size();
        save(file, "유중혁(3회차)", "회귀를 반복한다.", described, "[]");
        assertThat(eventsOf(file)).hasSize(before);
    }

    /**
     * 관계 단일 소유. 관계는 low 문서의 이벤트에만 실리고, high 쪽에서 바꿔도 이벤트는 low 문서가 받는다.
     * 바뀌지 않은 관계를 다시 저장하면 상대 문서의 이벤트는 없다.
     */
    @Test
    void aRelationTravelsOnlyWithItsLowDocument() throws Exception {
        UUID a = createDocument("CHARACTER", "유중혁", null);
        UUID b = createDocument("LOCATION", "충무로역", null);
        UUID low = lower(a, b);
        UUID high = low.equals(a) ? b : a;
        String lowTitle = low.equals(a) ? "유중혁" : "충무로역";
        String highTitle = low.equals(a) ? "충무로역" : "유중혁";

        // high 쪽 화면에서 본문을 쓰면서 관계를 건다. high 의 이벤트에는 그 관계가 없다.
        save(high, highTitle, "본문", "[]", relationTo(low, "처음 만난 곳"));
        JsonNode fromHigh = lastOf(high).payload();
        assertThat(fromHigh.get("changed_fields")).isEqualTo(json.readTree("[\"BODY\"]"));
        assertThat(fromHigh.get("relations")).isEqualTo(json.readTree("[]"));
        JsonNode owner = lastOf(low).payload();
        assertThat(owner.get("change_type").asString()).isEqualTo("UPDATED");
        assertThat(owner.get("changed_fields")).isEqualTo(json.readTree("[\"RELATIONS\"]"));
        assertThat(owner.get("relations")).isEqualTo(json.readTree("""
                [{"relation_id":"%s","target_document_id":"%s","description":"처음 만난 곳"}]
                """.formatted(relationId(low, high), high)));

        // 같은 내용을 그대로 다시 저장하면 어느 쪽도 바뀌지 않았다.
        int ownerEvents = eventsOf(low).size();
        int highEvents = eventsOf(high).size();
        save(high, highTitle, "본문", "[]", relationTo(low, "처음 만난 곳"));
        assertThat(eventsOf(low)).hasSize(ownerEvents);
        assertThat(eventsOf(high)).hasSize(highEvents);

        // owner 쪽에서 설명을 바꾸면 자기 RELATIONS 이고, high 문서는 받지 않는다.
        save(low, lowTitle, "", "[]", relationTo(high, "다시 만난 곳"));
        JsonNode fromLow = lastOf(low).payload();
        assertThat(fromLow.get("changed_fields")).isEqualTo(json.readTree("[\"RELATIONS\"]"));
        assertThat(fromLow.at("/relations/0/description").asString()).isEqualTo("다시 만난 곳");
        assertThat(eventsOf(high)).hasSize(highEvents);

        // high 쪽에서 관계만 지우면 owner 의 관계가 비고, high 자신은 바뀐 것이 없어 이벤트가 없다.
        save(high, highTitle, "본문", "[]", "[]");
        JsonNode removed = lastOf(low).payload();
        assertThat(removed.get("changed_fields")).isEqualTo(json.readTree("[\"RELATIONS\"]"));
        assertThat(removed.get("relations")).isEqualTo(json.readTree("[]"));
        assertThat(eventsOf(high)).hasSize(highEvents);
    }

    /** high 문서가 사라지면 그 관계를 가졌던 low 문서가 바뀐다. low 문서가 사라지면 그 관계는 DELETED 와 함께 끝난다. */
    @Test
    void deletingADocumentNotifiesOnlyTheOwnersOfItsRelations() throws Exception {
        UUID a = createDocument("CHARACTER", "유중혁", null);
        UUID b = createDocument("LOCATION", "충무로역", null);
        UUID low = lower(a, b);
        UUID high = low.equals(a) ? b : a;
        String lowTitle = low.equals(a) ? "유중혁" : "충무로역";
        save(low, lowTitle, "", "[]", relationTo(high, ""));
        int ownerEvents = eventsOf(low).size();

        // 휴지통은 관계를 지우지 않는다.
        mockMvc.perform(as(post("/files/" + high + "/trash"))).andExpect(status().isNoContent());
        assertThat(eventsOf(low)).hasSize(ownerEvents);

        mockMvc.perform(as(delete("/files/" + high))).andExpect(status().isNoContent());
        assertThat(lastOf(high).payload()).isEqualTo(json.readTree("""
                {"project_id":"%s","file_id":"%s","change_type":"DELETED",
                 "changed_fields":[],"file":null,"relations":null}
                """.formatted(project, high)));
        JsonNode owner = lastOf(low).payload();
        assertThat(owner.get("changed_fields")).isEqualTo(json.readTree("[\"RELATIONS\"]"));
        assertThat(owner.get("relations")).isEqualTo(json.readTree("[]"));
    }

    @Test
    void deletingTheOwnerDoesNotNotifyTheOtherSide() throws Exception {
        UUID a = createDocument("CHARACTER", "유중혁", null);
        UUID b = createDocument("LOCATION", "충무로역", null);
        UUID low = lower(a, b);
        UUID high = low.equals(a) ? b : a;
        String lowTitle = low.equals(a) ? "유중혁" : "충무로역";
        save(low, lowTitle, "", "[]", relationTo(high, ""));
        int highEvents = eventsOf(high).size();

        mockMvc.perform(as(post("/files/" + low + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/files/" + low))).andExpect(status().isNoContent());

        assertThat(lastOf(low).payload().get("change_type").asString()).isEqualTo("DELETED");
        assertThat(eventsOf(high)).hasSize(highEvents);
    }

    @Test
    void lockingPublishesNothing() throws Exception {
        UUID file = createDocument("CHARACTER", "유중혁", null);

        mockMvc.perform(as(body(put("/files/" + file + "/lock"), "{\"locked\":true}"))).andExpect(status().isOk());

        assertThat(eventsOf(file)).hasSize(1);
    }

    /** 프로젝트 영구 삭제는 tombstone 하나다. 문서마다 DELETED 를 내지 않는다. */
    @Test
    void deletingAProjectPublishesOneTombstone() throws Exception {
        UUID file = createDocument("CHARACTER", "유중혁", null);

        mockMvc.perform(as(post("/projects/" + project + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/projects/" + project))).andExpect(status().isNoContent());

        List<Event> events = eventsOf(project);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("ProjectDeleted");
        assertThat(events.getFirst().key()).isEqualTo(project.toString());
        assertThat(events.getFirst().payload()).isEqualTo(json.readTree("""
                {"project_id":"%s","reason":"PROJECT_DELETED"}
                """.formatted(project)));
        assertThat(eventsOf(file)).hasSize(1);
    }

    private UUID createDocument(String folderCode, String title, UUID episodeId) throws Exception {
        String episode = episodeId == null ? "" : ",\"episode_id\":\"" + episodeId + "\"";
        return idOf(mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode + "\",\"title\":\"" + title + "\""
                                + episode + "}")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private void move(UUID file, String payload) throws Exception {
        mockMvc.perform(as(body(patch("/files/" + file + "/position"), payload))).andExpect(status().isOk());
    }

    /** 화면이 저장할 때처럼 문서 전체를 보낸다. 본문은 문단 하나다. */
    private void save(UUID file, String title, String text, String properties, String relations) throws Exception {
        String paragraph = text.isEmpty()
                ? "{\"type\":\"paragraph\"}"
                : "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
        long revision = read(mockMvc.perform(as(get("/files/" + file + "/content"))).andReturn())
                .get("revision_no").asLong();
        mockMvc.perform(as(body(put("/files/" + file + "/content"), """
                        {"title":"%s","body":{"schema_version":1,"doc":{"type":"doc","content":[%s]}},
                         "properties":%s,"relations":%s}
                        """.formatted(title, paragraph, properties, relations)))
                        .header(HttpHeaders.IF_MATCH, "\"" + revision + "\""))
                .andExpect(status().isOk());
    }

    private static String relationTo(UUID target, String description) {
        return "[{\"relation_key\":\"related_character\",\"target_document_id\":\"" + target
                + "\",\"description\":\"" + description + "\"}]";
    }

    /** content 와 같은 기준으로 low 를 고른다: 소문자 uuid 문자열 순서 = PostgreSQL uuid 순서. */
    private static UUID lower(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) < 0 ? a : b;
    }

    private UUID relationId(UUID low, UUID high) {
        return jdbcClient.sql("select id from document_relations where low_document_id = :low"
                        + " and high_document_id = :high")
                .param("low", low).param("high", high)
                .query(UUID.class)
                .single();
    }

    private List<Event> eventsOf(UUID aggregate) {
        return jdbcClient
                .sql("select event_type, event_key, payload::text as payload from outbox_events"
                        + " where aggregate_id = :id order by seq")
                .param("id", aggregate)
                .query((rows, index) -> new Event(rows.getString("event_type"), rows.getString("event_key"),
                        json.readTree(rows.getString("payload"))))
                .list();
    }

    private Event lastOf(UUID aggregate) {
        return eventsOf(aggregate).getLast();
    }

    private UUID idOf(String response) {
        return UUID.fromString(json.readTree(response).get("id").asString());
    }
}
