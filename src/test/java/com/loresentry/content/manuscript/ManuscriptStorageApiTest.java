package com.loresentry.content.manuscript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class ManuscriptStorageApiTest extends ApiTestSupport {

    @Test
    void storesTheBodyOnlyAsCiphertextThatOpensWithTheProjectKey() throws Exception {
        UUID project = createProject();
        UUID file = createDocument(project, "유중혁");
        UUID other = createDocument(project, "김독자");

        save(file, "유중혁은 1863회차를 반복한 회귀자다.");

        Map<String, Object> row = jdbcClient.sql("select body_json_enc, body_text_enc, body_text_mac, "
                        + "body_text_length from document where id = :id")
                .param("id", file).query().singleRow();
        String text = manuscripts.open(project, file, Purpose.BODY_TEXT, (byte[]) row.get("body_text_enc"));
        assertThat(text).isEqualTo("유중혁은 1863회차를 반복한 회귀자다.");
        assertThat(manuscripts.open(project, file, Purpose.BODY_JSON, (byte[]) row.get("body_json_enc")))
                .contains("1863회차");
        assertThat((byte[]) row.get("body_text_mac")).isEqualTo(manuscripts.mac(project, text));
        assertThat(row.get("body_text_length")).isEqualTo(text.codePointCount(0, text.length()));
        assertThat(jdbcClient.sql("select count(*) from project_keys where project_id = :id")
                .param("id", project).query(Long.class).single()).isEqualTo(1);
        assertThat(manuscripts.open(project, other, Purpose.BODY_TEXT, jdbcClient
                .sql("select body_text_enc from document where id = :id").param("id", other)
                .query(byte[].class).single())).isEmpty();

        mockMvc.perform(as(get("/files/" + file + "/content")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.doc.content[0].content[0].text").value("유중혁은 1863회차를 반복한 회귀자다."));
        mockMvc.perform(as(get("/projects/" + project + "/search").param("q", "1863회차")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].file_id").value(file.toString()));
    }

    @Test
    void savingOnlyFormattingKeepsTheBodyUnchanged() throws Exception {
        UUID project = createProject();
        UUID file = createDocument(project, "유중혁");
        save(file, "회귀자");
        byte[] mac = jdbcClient.sql("select body_text_mac from document where id = :id")
                .param("id", file).query(byte[].class).single();

        save(file, "회귀자");

        assertThat(jdbcClient.sql("select body_text_mac from document where id = :id")
                .param("id", file).query(byte[].class).single()).isEqualTo(mac);
    }

    private UUID createProject() throws Exception {
        return idOf(mockMvc.perform(as(body(post("/projects"), "{\"name\":\"" + UUID.randomUUID() + "\"}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID createDocument(UUID project, String title) throws Exception {
        return idOf(mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"CHARACTER\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void save(UUID file, String text) throws Exception {
        long revision = json.readTree(mockMvc.perform(as(get("/files/" + file + "/content"))).andReturn()
                .getResponse().getContentAsString()).get("revision_no").asLong();
        mockMvc.perform(as(body(put("/files/" + file + "/content"), """
                        {"title":"%s","body":{"schema_version":1,"doc":{"type":"doc","content":[
                          {"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}},
                         "properties":[],"relations":[]}
                        """.formatted(json.readTree(mockMvc.perform(as(get("/files/" + file + "/content")))
                                .andReturn().getResponse().getContentAsString()).get("title").asString(), text)))
                        .header(HttpHeaders.IF_MATCH, "\"" + revision + "\""))
                .andExpect(status().isOk());
    }

    private UUID idOf(String response) {
        return UUID.fromString(json.readTree(response).get("id").asString());
    }
}
