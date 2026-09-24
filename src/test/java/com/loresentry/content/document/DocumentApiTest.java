package com.loresentry.content.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class DocumentApiTest extends ApiTestSupport {

    private UUID project;

    private UUID character;

    @BeforeEach
    void seed() throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"유리 정원의 기록\"}")))
                .andReturn();
        this.project = UUID.fromString(read(created).get("id").stringValue());
        this.character = createDocument("CHARACTER", "유중혁");
    }

    private UUID createDocument(String folderCode, String title) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode + "\",\"title\":\""
                                + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private MockHttpServletRequestBuilder saveOf(UUID fileId, long revision, String payload) {
        return as(body(put("/files/" + fileId + "/content"), payload))
                .header(HttpHeaders.IF_MATCH, "\"" + revision + "\"");
    }

    private long revisionOf(UUID fileId) throws Exception {
        MvcResult result = mockMvc.perform(as(get("/files/" + fileId + "/content"))).andReturn();
        return read(result).get("revision_no").asLong();
    }

    @Test
    void servesAnEmptyDocumentAfterCreation() throws Exception {
        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("유중혁"))
                .andExpect(jsonPath("$.folder_code").value("CHARACTER"))
                .andExpect(jsonPath("$.body_md").value(""))
                .andExpect(jsonPath("$.revision_no").value(0))
                .andExpect(jsonPath("$.properties.length()").value(0))
                .andExpect(jsonPath("$.relations.length()").value(0));
    }

    @Test
    void savesTitleBodyPropertiesAndRelationsTogether() throws Exception {
        UUID other = createDocument("CHARACTER", "김독자");

        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body_md":"회귀를 반복하는 인물이다.",
                 "properties":[{"key":"description","value":"세 번째 등장인물"}],
                 "relations":[{"relation_key":"related_character","target_document_id":"%s"}]}
                """.formatted(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision_no").value(1))
                .andExpect(jsonPath("$.char_count").value("회귀를 반복하는 인물이다.".length()))
                .andExpect(jsonPath("$.properties[0].key").value("description"))
                .andExpect(jsonPath("$.relations[0].target_document_id").value(other.toString()));
    }

    @Test
    void demandsAnIfMatchRevision() throws Exception {
        // 조건 없는 저장은 남의 변경을 조용히 덮어쓴다.
        mockMvc.perform(as(body(put("/files/" + character + "/content"),
                        "{\"title\":\"x\",\"body_md\":\"\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(as(body(put("/files/" + character + "/content"),
                        "{\"title\":\"x\",\"body_md\":\"\"}"))
                        .header(HttpHeaders.IF_MATCH, "not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answersAConflictWithTheCurrentDocumentAndCommonAncestor() throws Exception {
        mockMvc.perform(saveOf(character, 0,
                        "{\"title\":\"유중혁\",\"body_md\":\"첫 저장\"}")).andExpect(status().isOk());

        // revision 0 을 들고 있던 오래된 탭이 다시 저장한다.
        MvcResult conflict = mockMvc.perform(saveOf(character, 0,
                        "{\"title\":\"유중혁\",\"body_md\":\"낡은 탭의 저장\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_CONFLICT"))
                .andExpect(jsonPath("$.current.body_md").value("첫 저장"))
                .andReturn();

        // 첫 저장이 AUTO 버전을 남겼으므로 revision 1 의 스냅샷은 있다. 0 의 것은 없다.
        assertThat(read(conflict).get("base").isNull()).isTrue();
    }

    @Test
    void carriesTheCommonAncestorWhenThatRevisionWasVersioned() throws Exception {
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body_md\":\"1\"}"))
                .andExpect(status().isOk());

        // revision 1 은 AUTO 버전으로 남았다. 그 revision 을 들고 충돌하면 base 가 온다.
        MvcResult conflict = mockMvc.perform(saveOf(character, 1, "{\"title\":\"유중혁\",\"body_md\":\"2\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(read(conflict).get("revision_no").asLong()).isEqualTo(2);

        mockMvc.perform(saveOf(character, 1, "{\"title\":\"유중혁\",\"body_md\":\"낡은 탭\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.base.body_md").value("1"));
    }

    @Test
    void treatsTheSameSaveIdAsARetry() throws Exception {
        String saveId = UUID.randomUUID().toString();

        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body_md\":\"한 번\"}")
                        .header(DocumentController.SAVE_ID_HEADER, saveId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision_no").value(1));

        // 응답을 받지 못한 클라이언트가 같은 저장을 다시 보낸다. revision 이 또 올라가면 안 된다.
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body_md\":\"한 번\"}")
                        .header(DocumentController.SAVE_ID_HEADER, saveId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision_no").value(1))
                .andExpect(jsonPath("$.body_md").value("한 번"));
    }

    @Test
    void refusesRelationsOutsideTheProjectOrToItself() throws Exception {
        MvcResult other = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"다른 프로젝트\"}")))
                .andReturn();
        UUID otherProject = UUID.fromString(read(other).get("id").stringValue());
        MvcResult foreign = mockMvc.perform(as(body(post("/projects/" + otherProject + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"CHARACTER\",\"title\":\"남\"}")))
                .andReturn();
        UUID foreignId = UUID.fromString(read(foreign).get("id").stringValue());

        // 외래 키는 문서가 존재하는지만 본다. 프로젝트 경계는 여기서 지켜야 한다.
        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body_md":"",
                 "relations":[{"relation_key":"related_character","target_document_id":"%s"}]}
                """.formatted(foreignId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RELATION_TARGET"));

        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body_md":"",
                 "relations":[{"relation_key":"related_character","target_document_id":"%s"}]}
                """.formatted(character)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RELATION_TARGET"));
    }

    @Test
    void refusesRelationsToTrashedDocuments() throws Exception {
        UUID other = createDocument("CHARACTER", "김독자");
        mockMvc.perform(as(post("/files/" + other + "/trash"))).andExpect(status().isNoContent());

        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body_md":"",
                 "relations":[{"relation_key":"related_character","target_document_id":"%s"}]}
                """.formatted(other)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RELATION_TARGET"));
    }

    @Test
    void blocksEditingALockedDocumentButKeepsItReadable() throws Exception {
        mockMvc.perform(as(body(put("/files/" + character + "/lock"), "{\"locked\":true}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));

        mockMvc.perform(as(get("/files/" + character + "/content"))).andExpect(status().isOk());

        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body_md\":\"막힌다\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_LOCKED"));

        mockMvc.perform(as(body(put("/files/" + character + "/lock"), "{\"locked\":false}")))
                .andExpect(status().isOk());
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body_md\":\"이제 된다\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void savesAndListsNamedVersions() throws Exception {
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body_md\":\"1회차\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(body(post("/files/" + character + "/versions"), "{\"label\":\"초고\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("NAMED"))
                .andExpect(jsonPath("$.label").value("초고"))
                .andExpect(jsonPath("$.snapshot.body_md").value("1회차"));

        mockMvc.perform(as(get("/files/" + character + "/versions")))
                .andExpect(status().isOk())
                // 첫 저장이 남긴 AUTO 하나와 방금의 NAMED 하나.
                .andExpect(jsonPath("$.versions.length()").value(2));
    }

    @Test
    void restoresAsANewRevisionAndKeepsWhatItReplaced() throws Exception {
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body_md\":\"첫 원고\"}"))
                .andExpect(status().isOk());
        MvcResult named = mockMvc.perform(as(body(post("/files/" + character + "/versions"),
                        "{\"label\":\"초고\"}"))).andReturn();
        UUID versionId = UUID.fromString(read(named).get("id").stringValue());

        mockMvc.perform(saveOf(character, 1, "{\"title\":\"유중혁\",\"body_md\":\"고친 원고\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(post("/files/" + character + "/versions/" + versionId + "/restore"))
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body_md").value("첫 원고"))
                // 되감지 않고 새 변경으로 저장한다.
                .andExpect(jsonPath("$.revision_no").value(3));

        // 복원 직전 상태가 버전으로 남아 있어 돌아올 자리가 있다.
        mockMvc.perform(as(get("/files/" + character + "/versions")))
                .andExpect(jsonPath("$.versions[?(@.kind == 'RESTORE')].snapshot.body_md")
                        .value(org.hamcrest.Matchers.hasItem("고친 원고")));
    }

    @Test
    void refusesToRestoreOverSomeoneElsesSave() throws Exception {
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body_md\":\"1\"}"))
                .andExpect(status().isOk());
        MvcResult named = mockMvc.perform(as(body(post("/files/" + character + "/versions"), "{}")))
                .andReturn();
        UUID versionId = UUID.fromString(read(named).get("id").stringValue());
        mockMvc.perform(saveOf(character, 1, "{\"title\":\"유중혁\",\"body_md\":\"2\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(post("/files/" + character + "/versions/" + versionId + "/restore"))
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_CONFLICT"));
    }

    @Test
    void deletesAVersion() throws Exception {
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body_md\":\"1\"}"))
                .andExpect(status().isOk());
        MvcResult named = mockMvc.perform(as(body(post("/files/" + character + "/versions"), "{}")))
                .andReturn();
        UUID versionId = UUID.fromString(read(named).get("id").stringValue());

        mockMvc.perform(as(delete("/files/" + character + "/versions/" + versionId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/files/" + character + "/versions/" + versionId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
    }

    @Test
    void keepsTrashedDocumentsOutOfReach() throws Exception {
        mockMvc.perform(as(post("/files/" + character + "/trash"))).andExpect(status().isNoContent());

        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body_md\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidesAnotherOwnersDocument() throws Exception {
        mockMvc.perform(as(get("/files/" + character + "/content"), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        mockMvc.perform(as(get("/files/" + character + "/versions"), stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsABlankTitleOnSave() throws Exception {
        mockMvc.perform(saveOf(character, revisionOf(character), "{\"title\":\"   \",\"body_md\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_TITLE"));
    }

    @Test
    void refusesASaveThatWouldCollideWithASiblingTitle() throws Exception {
        createDocument("CHARACTER", "김독자");

        mockMvc.perform(saveOf(character, 0, "{\"title\":\"김독자\",\"body_md\":\"\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_TITLE_TAKEN"));
    }
}
