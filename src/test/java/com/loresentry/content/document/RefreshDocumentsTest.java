package com.loresentry.content.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/** 최신화가 문서를 읽고, 기준 버전을 남기고, 잠그고 푸는 창구. 모든 DB 작업은 호출자의 트랜잭션 안에서만 돈다. */
class RefreshDocumentsTest extends ApiTestSupport {

    @Autowired
    private RefreshDocuments documents;

    @Autowired
    private TransactionTemplate transaction;

    private UUID project;

    private UUID character;

    private UUID chapter;

    @BeforeEach
    void seed() throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"최신화 창구\"}"))).andReturn();
        project = UUID.fromString(read(created).get("id").stringValue());
        character = createDocument("CHARACTER", "유나");
        chapter = createDocument("MANUSCRIPT", "1화");
        mockMvc.perform(as(body(put("/files/" + character + "/content"), """
                        {"title":"유나","body":{"schema_version":1,"doc":{"type":"doc","content":[
                            {"type":"paragraph","content":[{"type":"text","text":"파란 망토"}]}]}},
                         "properties":[{"key":"description","value":"주인공"}],
                         "relations":[{"relation_key":"related_manuscript","target_document_id":"%s",
                                       "description":"첫 등장"}]}
                        """.formatted(chapter)))
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
    }

    private UUID createDocument(String folderCode, String title) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode + "\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    @Test
    void readsTheLiveDocumentsWithTheirPropertiesAndRelations() {
        Map<UUID, RefreshDocuments.Live> live = transaction.execute(status ->
                documents.read(project, List.of(character, chapter, UUID.randomUUID())));

        assertThat(live).containsOnlyKeys(character, chapter);
        RefreshDocuments.Live hero = live.get(character);
        assertThat(hero.revisionNo()).isEqualTo(1);
        assertThat(hero.folderCode()).isEqualTo("CHARACTER");
        assertThat(hero.snapshot().properties()).containsExactly(new DocumentSnapshot.TextProperty("description", "주인공"));
        assertThat(hero.snapshot().relations()).containsExactly(
                new DocumentSnapshot.Relation("related_manuscript", chapter, "첫 등장"));
        assertThat(BodyText.extract(hero.snapshot().body().get("doc"))).isEqualTo("파란 망토");
        assertThat(live.get(chapter).snapshot().relations()).containsExactly(
                new DocumentSnapshot.Relation("related_character", character, "첫 등장"));
    }

    @Test
    void keepsRefreshBasesOutOfTheVersionListAndDeletesOnlyThem() throws Exception {
        UUID base = transaction.execute(status -> {
            RefreshDocuments.Live hero = documents.read(project, List.of(character)).get(character);
            return documents.insertRefreshBase(character, hero.revisionNo(), hero.snapshot());
        });

        assertThat(transaction.execute(status -> documents.refreshBases(List.of(base))).get(base).title())
                .isEqualTo("유나");
        mockMvc.perform(as(get("/files/" + character + "/versions")))
                .andExpect(jsonPath("$.versions[?(@.kind == 'REFRESH_BASE')]").isEmpty());

        transaction.executeWithoutResult(status -> documents.deleteRefreshBases(List.of(base)));
        Map<UUID, DocumentSnapshot> remaining = transaction.execute(status -> documents.refreshBases(List.of(base)));
        assertThat(remaining).isEmpty();
        // 저장이 남긴 사용자 버전(AUTO)은 그대로다.
        mockMvc.perform(as(get("/files/" + character + "/versions")))
                .andExpect(jsonPath("$.versions.length()").value(1));
    }

    @Test
    void locksForARunAndUnlocksOnlyThatRunsLock() throws Exception {
        UUID run = jdbcClient.sql("insert into refresh_runs (project_id, requested_by, status) "
                        + "values (:project, :owner, 'GENERATING') returning id")
                .param("project", project).param("owner", owner).query(UUID.class).single();
        mockMvc.perform(as(body(put("/files/" + chapter + "/lock"), "{\"locked\":true}")))
                .andExpect(status().isOk());

        transaction.executeWithoutResult(status -> documents.lock(run, List.of(character, chapter)));
        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.refresh_locked").value(true));

        transaction.executeWithoutResult(status -> documents.unlock(run));
        mockMvc.perform(as(get("/files/" + character + "/content")))
                .andExpect(jsonPath("$.locked").value(false));
        // 사용자가 건 잠금은 최신화가 끝나도 남는다.
        mockMvc.perform(as(get("/files/" + chapter + "/content")))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.refresh_locked").value(false));
    }
}
