package com.loresentry.content.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
                .andExpect(jsonPath("$.body.doc.content[0].type").value("paragraph"))
                .andExpect(jsonPath("$.revision_no").value(0))
                .andExpect(jsonPath("$.properties.length()").value(0))
                .andExpect(jsonPath("$.relations.length()").value(0));
    }

    @Test
    void savesTitleBodyPropertiesAndRelationsTogether() throws Exception {
        UUID other = createDocument("CHARACTER", "김독자");

        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"회귀를 반복하는 인물이다."}]}]}},
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
    void makesARelationVisibleFromTheOtherSide() throws Exception {
        // 사용자는 한쪽에서만 이어 놓고 반대쪽 문서를 열어 그 관계를 찾는다.
        UUID place = createDocument("LOCATION", "충무로역");

        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],
                 "relations":[{"relation_key":"related_place","target_document_id":"%s"}]}
                """.formatted(place)))
                .andExpect(status().isOk());

        // 장소 쪽에서는 캐릭터를 가리키는 키로 보인다. 키는 가리키는 쪽의 분류가 정한다.
        mockMvc.perform(as(get("/files/" + place + "/content")))
                .andExpect(jsonPath("$.relations.length()").value(1))
                .andExpect(jsonPath("$.relations[0].relation_key").value("related_character"))
                .andExpect(jsonPath("$.relations[0].target_document_id").value(character.toString()))
                // 관계는 본문이 아니다. 저 문서를 열어 둔 편집기가 충돌로 떨어지면 안 된다.
                .andExpect(jsonPath("$.revision_no").value(0));
    }

    /**
     * 사용자가 말한 그대로의 경로다: 원고에서 캐릭터를 걸면 캐릭터의 "관련 원고"에 그 원고가 있어야
     * 한다. 캐릭터↔장소만 검증해 두면 원고 쪽 분류가 빠져도 드러나지 않는다.
     */
    @Test
    void showsTheManuscriptUnderTheCharacterItNames() throws Exception {
        UUID manuscript = createDocument("MANUSCRIPT", "제1화 회귀");

        mockMvc.perform(saveOf(manuscript, 0, """
                {"title":"제1화 회귀","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],
                 "relations":[{"relation_key":"related_character","target_document_id":"%s"}]}
                """.formatted(character)))
                .andExpect(status().isOk());

        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(jsonPath("$.relations.length()").value(1))
                .andExpect(jsonPath("$.relations[0].relation_key").value("related_manuscript"))
                .andExpect(jsonPath("$.relations[0].target_document_id").value(manuscript.toString()));
    }

    /**
     * 반대쪽 문서를 열어 <b>본문만</b> 고치고 저장하면, 브라우저는 읽은 관계를 그대로 돌려보낸다.
     * 한 행이 두 문서의 것이라, 그 저장이 쌍을 다시 쓰면서 원래 행을 지우면 관계가 사라진다.
     */
    @Test
    void keepsBothSidesWhenEachDocumentSavesInTurn() throws Exception {
        UUID manuscript = createDocument("MANUSCRIPT", "제1화 회귀");

        mockMvc.perform(saveOf(manuscript, 0, """
                {"title":"제1화 회귀","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],
                 "relations":[{"relation_key":"related_character","target_document_id":"%s","description":"첫 등장"}]}
                """.formatted(character)))
                .andExpect(status().isOk());

        // 캐릭터 쪽이 읽은 것을 그대로 돌려보낸다 — 화면이 저장할 때 하는 일이다.
        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],
                 "relations":[{"relation_key":"related_manuscript","target_document_id":"%s","description":"첫 등장"}]}
                """.formatted(manuscript)))
                .andExpect(status().isOk());

        mockMvc.perform(as(get("/files/" + manuscript + "/content")))
                .andExpect(jsonPath("$.relations.length()").value(1))
                .andExpect(jsonPath("$.relations[0].relation_key").value("related_character"))
                .andExpect(jsonPath("$.relations[0].target_document_id").value(character.toString()))
                .andExpect(jsonPath("$.relations[0].description").value("첫 등장"));

        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(jsonPath("$.relations.length()").value(1))
                .andExpect(jsonPath("$.relations[0].relation_key").value("related_manuscript"))
                .andExpect(jsonPath("$.relations[0].target_document_id").value(manuscript.toString()));
    }

    /** 관계에는 방향이 없다. 두 문서 사이의 연결은 표에 <b>한 행</b>이다. */
    @Test
    void storesOneRecordForThePairNotOnePerDirection() throws Exception {
        UUID manuscript = createDocument("MANUSCRIPT", "제1화 회귀");
        mockMvc.perform(saveOf(manuscript, 0, """
                {"title":"제1화 회귀","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],
                 "relations":[{"relation_key":"related_character","target_document_id":"%s"}]}
                """.formatted(character)))
                .andExpect(status().isOk());

        assertThat(jdbcClient
                .sql("select count(*) from document_relations where :a in (low_document_id, high_document_id)"
                        + " and :b in (low_document_id, high_document_id)")
                .param("a", manuscript).param("b", character)
                .query(Integer.class).single())
                .isEqualTo(1);

        // 반대쪽에서 저장해도 행이 늘지 않는다 — 맞춰 줄 두 번째 행이 없다.
        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],
                 "relations":[{"relation_key":"related_manuscript","target_document_id":"%s"}]}
                """.formatted(manuscript)))
                .andExpect(status().isOk());

        assertThat(jdbcClient.sql("select count(*) from document_relations").query(Integer.class).single())
                .isEqualTo(1);
    }

    /**
     * 키를 행에 적지 않고 대상 문서의 분류에서 꺼내므로, 문서를 다른 분류로 옮기면 반대쪽에서 보는
     * 키도 따라 바뀐다. 적어 두었다면 옮긴 뒤로 어긋난 채 남는다.
     */
    @Test
    void followsTheDocumentWhenItsFolderChanges() throws Exception {
        UUID place = createDocument("LOCATION", "충무로역");
        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],
                 "relations":[{"relation_key":"related_place","target_document_id":"%s"}]}
                """.formatted(place)))
                .andExpect(status().isOk());

        mockMvc.perform(as(body(patch("/files/" + place + "/position"),
                "{\"folder_code\":\"ORGANIZATION\"}")))
                .andExpect(status().isOk());

        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(jsonPath("$.relations[0].relation_key").value("related_organization"));
    }

    /**
     * 휴지통에 있는 문서와의 관계는 화면에 내놓지 않지만 <b>지우지도 않는다.</b> 그 사이에 상대
     * 문서를 저장했다고 관계가 사라지면, 되살렸을 때 돌아올 자리가 없다.
     */
    @Test
    void keepsARelationToATrashedDocumentUntilItComesBack() throws Exception {
        UUID place = createDocument("LOCATION", "충무로역");
        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],
                 "relations":[{"relation_key":"related_place","target_document_id":"%s"}]}
                """.formatted(place)))
                .andExpect(status().isOk());

        mockMvc.perform(as(post("/files/" + place + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(jsonPath("$.relations.length()").value(0));

        // 화면이 본 그대로(관계 없음) 저장한다. 그래도 행은 남아야 한다.
        mockMvc.perform(saveOf(character, 1, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],"relations":[]}
                """))
                .andExpect(status().isOk());

        mockMvc.perform(as(post("/files/" + place + "/restore"))).andExpect(status().isOk());
        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(jsonPath("$.relations.length()").value(1))
                .andExpect(jsonPath("$.relations[0].target_document_id").value(place.toString()));
    }

    @Test
    void removesTheOtherSideWhenTheRelationGoesAway() throws Exception {
        UUID place = createDocument("LOCATION", "충무로역");
        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],
                 "relations":[{"relation_key":"related_place","target_document_id":"%s"}]}
                """.formatted(place)))
                .andExpect(status().isOk());

        mockMvc.perform(saveOf(character, 1, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],"relations":[]}
                """))
                .andExpect(status().isOk());

        // 한쪽에서 지운 관계가 반대쪽에 남으면 다시 살아난 것처럼 보인다.
        mockMvc.perform(as(get("/files/" + place + "/content")))
                .andExpect(jsonPath("$.relations.length()").value(0));
    }

    @Test
    void letsTheOtherSideDropTheRelationToo() throws Exception {
        UUID place = createDocument("LOCATION", "충무로역");
        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],
                 "relations":[{"relation_key":"related_place","target_document_id":"%s"}]}
                """.formatted(place)))
                .andExpect(status().isOk());

        // 한 행이 두 문서의 것이므로 어느 쪽에서든 끊을 수 있다.
        mockMvc.perform(saveOf(place, 0, """
                {"title":"충무로역","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},"properties":[],"relations":[]}
                """))
                .andExpect(status().isOk());

        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(jsonPath("$.relations.length()").value(0));
    }

    @Test
    void keepsTextThatLooksLikeMarkdownAsPlainText() throws Exception {
        // Markdown 으로 저장하던 동안 이런 문단은 다시 열 때 제목·목록·밑줄로 바뀌었다.
        for (String text : new String[] {"# 해시로 시작하는 문장", "1. 번호처럼 보이는 문장",
                "++더하기로 감싼 문장++", "*별표로 감싼 문장*"}) {
            String title = "원고 " + text.hashCode();
            UUID file = createDocument("MANUSCRIPT", title);
            mockMvc.perform(saveOf(file, 0, """
                    {"title":"%s","body":{"schema_version":1,"doc":{"type":"doc","content":[
                        {"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}},
                     "properties":[],"relations":[]}
                    """.formatted(title, text)))
                    .andExpect(status().isOk());

            mockMvc.perform(as(get("/files/" + file + "/content")))
                    .andExpect(jsonPath("$.body.doc.content[0].type").value("paragraph"))
                    .andExpect(jsonPath("$.body.doc.content[0].content[0].text").value(text));
        }
    }

    @Test
    void refusesNodesAndMarksTheEditorDoesNotMake() throws Exception {
        // 모르는 노드를 저장하면 다른 클라이언트가 열 수 없는 문서가 된다. 조용히 지우지도 않는다.
        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[
                    {"type":"table","content":[]}]}},"properties":[],"relations":[]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[
                    {"type":"paragraph","content":[
                        {"type":"text","marks":[{"type":"highlight"}],"text":"칠"}]}]}},
                 "properties":[],"relations":[]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void refusesABodyWithoutADocOrSchemaVersion() throws Exception {
        mockMvc.perform(saveOf(character, 0,
                        "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"doc":{"type":"doc","content":[]}}}
                """))
                .andExpect(status().isBadRequest());
        // 옛 모양은 더 이상 받지 않는다. 저장은 body 를 요구한다.
        mockMvc.perform(saveOf(character, 0,
                        "{\"title\":\"유중혁\",\"body_md\":\"옛 본문\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void countsCharactersFromTheExtractedTextNotTheMarkup() throws Exception {
        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[
                    {"type":"paragraph","content":[
                        {"type":"text","marks":[{"type":"bold"}],"text":"가나"},
                        {"type":"text","text":"다"}]},
                    {"type":"paragraph","content":[{"type":"text","text":"라"}]}]}},
                 "properties":[],"relations":[]}
                """))
                .andExpect(status().isOk())
                // 굵게 표시는 글자가 아니고, 문단 사이 줄바꿈은 세지 않는다.
                .andExpect(jsonPath("$.char_count").value(4));
    }

    @Test
    void readsALegacyMarkdownRowAsLegacyBody() throws Exception {
        // 변환 전 행이 남아 있다. 서버는 Markdown 을 해석하지 않고 그대로 넘기고, 프론트가 바꾼다.
        jdbcClient.sql("update document set body_json = null, body_md = :md where id = :id")
                .param("md", "# 옛 본문").param("id", character).update();

        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.legacy_body_md").value("# 옛 본문"));
    }

    @Test
    void demandsAnIfMatchRevision() throws Exception {
        // 조건 없는 저장은 남의 변경을 조용히 덮어쓴다.
        mockMvc.perform(as(body(put("/files/" + character + "/content"),
                        "{\"title\":\"x\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}}}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(as(body(put("/files/" + character + "/content"),
                        "{\"title\":\"x\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}}}"))
                        .header(HttpHeaders.IF_MATCH, "not-a-number"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answersAConflictWithTheCurrentDocumentAndCommonAncestor() throws Exception {
        mockMvc.perform(saveOf(character, 0,
                        "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"첫 저장\"}]}]}}}")).andExpect(status().isOk());

        // revision 0 을 들고 있던 오래된 탭이 다시 저장한다.
        MvcResult conflict = mockMvc.perform(saveOf(character, 0,
                        "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"낡은 탭의 저장\"}]}]}}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_CONFLICT"))
                .andExpect(jsonPath("$.current.body.doc.content[0].content[0].text").value("첫 저장"))
                .andReturn();

        // 첫 저장이 AUTO 버전을 남겼으므로 revision 1 의 스냅샷은 있다. 0 의 것은 없다.
        assertThat(read(conflict).get("base").isNull()).isTrue();
    }

    @Test
    void carriesTheCommonAncestorWhenThatRevisionWasVersioned() throws Exception {
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"1\"}]}]}}}"))
                .andExpect(status().isOk());

        // revision 1 은 AUTO 버전으로 남았다. 그 revision 을 들고 충돌하면 base 가 온다.
        MvcResult conflict = mockMvc.perform(saveOf(character, 1, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"2\"}]}]}}}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(read(conflict).get("revision_no").asLong()).isEqualTo(2);

        mockMvc.perform(saveOf(character, 1, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"낡은 탭\"}]}]}}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.base.body.doc.content[0].content[0].text").value("1"));
    }

    @Test
    void treatsTheSameSaveIdAsARetry() throws Exception {
        String saveId = UUID.randomUUID().toString();

        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"한 번\"}]}]}}}")
                        .header(DocumentController.SAVE_ID_HEADER, saveId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision_no").value(1));

        // 응답을 받지 못한 클라이언트가 같은 저장을 다시 보낸다. revision 이 또 올라가면 안 된다.
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"한 번\"}]}]}}}")
                        .header(DocumentController.SAVE_ID_HEADER, saveId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision_no").value(1))
                .andExpect(jsonPath("$.body.doc.content[0].content[0].text").value("한 번"));
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
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},
                 "relations":[{"relation_key":"related_character","target_document_id":"%s"}]}
                """.formatted(foreignId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RELATION_TARGET"));

        mockMvc.perform(saveOf(character, 0, """
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},
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
                {"title":"유중혁","body":{"schema_version":1,"doc":{"type":"doc","content":[{"type":"paragraph"}]}},
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

        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"막힌다\"}]}]}}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_LOCKED"));

        mockMvc.perform(as(body(put("/files/" + character + "/lock"), "{\"locked\":false}")))
                .andExpect(status().isOk());
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"이제 된다\"}]}]}}}"))
                .andExpect(status().isOk());
    }

    @Test
    void savesAndListsNamedVersions() throws Exception {
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"1회차\"}]}]}}}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(body(post("/files/" + character + "/versions"), "{\"label\":\"초고\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("NAMED"))
                .andExpect(jsonPath("$.label").value("초고"))
                .andExpect(jsonPath("$.snapshot.body.doc.content[0].content[0].text").value("1회차"));

        mockMvc.perform(as(get("/files/" + character + "/versions")))
                .andExpect(status().isOk())
                // 첫 저장이 남긴 AUTO 하나와 방금의 NAMED 하나.
                .andExpect(jsonPath("$.versions.length()").value(2));
    }

    @Test
    void restoresAsANewRevisionAndKeepsWhatItReplaced() throws Exception {
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"첫 원고\"}]}]}}}"))
                .andExpect(status().isOk());
        MvcResult named = mockMvc.perform(as(body(post("/files/" + character + "/versions"),
                        "{\"label\":\"초고\"}"))).andReturn();
        UUID versionId = UUID.fromString(read(named).get("id").stringValue());

        mockMvc.perform(saveOf(character, 1, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"고친 원고\"}]}]}}}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(post("/files/" + character + "/versions/" + versionId + "/restore"))
                        .header(HttpHeaders.IF_MATCH, "\"2\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.doc.content[0].content[0].text").value("첫 원고"))
                // 되감지 않고 새 변경으로 저장한다.
                .andExpect(jsonPath("$.revision_no").value(3));

        // 복원 직전 상태가 버전으로 남아 있어 돌아올 자리가 있다.
        mockMvc.perform(as(get("/files/" + character + "/versions")))
                .andExpect(jsonPath("$.versions[?(@.kind == 'RESTORE')].snapshot.body.doc.content[0].content[0].text")
                        .value(org.hamcrest.Matchers.hasItem("고친 원고")));
    }

    @Test
    void refusesToRestoreOverSomeoneElsesSave() throws Exception {
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"1\"}]}]}}}"))
                .andExpect(status().isOk());
        MvcResult named = mockMvc.perform(as(body(post("/files/" + character + "/versions"), "{}")))
                .andReturn();
        UUID versionId = UUID.fromString(read(named).get("id").stringValue());
        mockMvc.perform(saveOf(character, 1, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"2\"}]}]}}}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(post("/files/" + character + "/versions/" + versionId + "/restore"))
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOCUMENT_CONFLICT"));
    }

    @Test
    void deletesAVersion() throws Exception {
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"1\"}]}]}}}"))
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
        mockMvc.perform(saveOf(character, 0, "{\"title\":\"유중혁\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}]}]}}}"))
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
        mockMvc.perform(saveOf(character, revisionOf(character), "{\"title\":\"   \",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_TITLE"));
    }

    @Test
    void refusesASaveThatWouldCollideWithASiblingTitle() throws Exception {
        createDocument("CHARACTER", "김독자");

        mockMvc.perform(saveOf(character, 0, "{\"title\":\"김독자\",\"body\":{\"schema_version\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_TITLE_TAKEN"));
    }
}
