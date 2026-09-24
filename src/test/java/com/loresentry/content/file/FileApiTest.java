package com.loresentry.content.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

class FileApiTest extends ApiTestSupport {

    private UUID project;

    @BeforeEach
    void createProject() throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"유리 정원의 기록\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        this.project = UUID.fromString(read(result).get("id").stringValue());
    }

    private UUID createDocument(String folderCode, String title) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode + "\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private UUID createChapter(UUID episodeId, String title) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"MANUSCRIPT\",\"episode_id\":\""
                                + episodeId + "\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private UUID createEpisode(String title) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"episode\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private List<String> documentTitlesInOrder() throws Exception {
        MvcResult result = mockMvc.perform(as(get("/projects/" + project + "/files")))
                .andExpect(status().isOk())
                .andReturn();
        List<String> titles = new ArrayList<>();
        for (JsonNode document : read(result).get("documents")) {
            titles.add(document.get("title").stringValue());
        }
        return titles;
    }

    @Test
    void servesTheSevenSeededFoldersWithAnEmptyProject() throws Exception {
        mockMvc.perform(as(get("/projects/" + project + "/files")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folders.length()").value(7))
                .andExpect(jsonPath("$.folders[0].code").value("WORLDVIEW"))
                .andExpect(jsonPath("$.folders[3].code").value("MANUSCRIPT"))
                .andExpect(jsonPath("$.episodes.length()").value(0))
                .andExpect(jsonPath("$.documents.length()").value(0));
    }

    @Test
    void createsDocumentsAndEpisodes() throws Exception {
        createDocument("CHARACTER", "유중혁");
        UUID episode = createEpisode("1부 멸망의 시작");
        createChapter(episode, "1화");

        mockMvc.perform(as(get("/projects/" + project + "/files")))
                .andExpect(jsonPath("$.episodes.length()").value(1))
                .andExpect(jsonPath("$.episodes[0].name").value("1부 멸망의 시작"))
                .andExpect(jsonPath("$.documents.length()").value(2));
    }

    @Test
    void refusesAnEpisodeOutsideTheManuscriptFolder() throws Exception {
        UUID episode = createEpisode("1부");

        // DB 에도 같은 규칙이 CHECK 로 있지만, 거기 걸리면 클라이언트가 받는 것은 제약 이름뿐이다.
        mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"CHARACTER\",\"episode_id\":\""
                                + episode + "\",\"title\":\"엉뚱한 곳\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_LOCATION"));
    }

    @Test
    void refusesAnEpisodeFromAnotherProject() throws Exception {
        UUID episode = createEpisode("1부");
        MvcResult other = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"다른 프로젝트\"}")))
                .andReturn();
        UUID otherProject = UUID.fromString(read(other).get("id").stringValue());

        mockMvc.perform(as(body(post("/projects/" + otherProject + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"MANUSCRIPT\",\"episode_id\":\""
                                + episode + "\",\"title\":\"남의 에피소드\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_LOCATION"));
    }

    @Test
    void refusesAnUnknownFolderCode() throws Exception {
        mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"NOPE\",\"title\":\"x\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_LOCATION"));
    }

    @Test
    void refusesDuplicateTitlesInTheSameFolderOnly() throws Exception {
        createDocument("CHARACTER", "유중혁");

        mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"CHARACTER\",\"title\":\"유중혁\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_TITLE_TAKEN"));

        // 다른 분류에는 같은 제목을 둘 수 있다.
        createDocument("LOCATION", "유중혁");
    }

    @Test
    void appendsNewFilesAfterTheExistingOnes() throws Exception {
        createDocument("CHARACTER", "첫째");
        createDocument("CHARACTER", "둘째");
        createDocument("CHARACTER", "셋째");

        assertThat(documentTitlesInOrder()).containsExactly("첫째", "둘째", "셋째");
    }

    @Test
    void movesAFileBeforeAChosenSibling() throws Exception {
        createDocument("CHARACTER", "첫째");
        createDocument("CHARACTER", "둘째");
        UUID third = createDocument("CHARACTER", "셋째");
        UUID first = UUID.fromString(read(mockMvc.perform(as(get("/projects/" + project + "/files")))
                .andReturn()).get("documents").get(0).get("id").stringValue());

        mockMvc.perform(as(body(patch("/files/" + third + "/position"),
                        "{\"folder_code\":\"CHARACTER\",\"before_file_id\":\"" + first + "\"}")))
                .andExpect(status().isOk());

        assertThat(documentTitlesInOrder()).containsExactly("셋째", "첫째", "둘째");
    }

    @Test
    void movesAFileToTheEndWhenNoSiblingIsNamed() throws Exception {
        UUID first = createDocument("CHARACTER", "첫째");
        createDocument("CHARACTER", "둘째");

        mockMvc.perform(as(body(patch("/files/" + first + "/position"), "{\"folder_code\":\"CHARACTER\"}")))
                .andExpect(status().isOk());

        assertThat(documentTitlesInOrder()).containsExactly("둘째", "첫째");
    }

    @Test
    void movesAChapterIntoAndOutOfAnEpisode() throws Exception {
        UUID episode = createEpisode("1부");
        UUID chapter = createDocument("MANUSCRIPT", "1화");

        mockMvc.perform(as(body(patch("/files/" + chapter + "/position"),
                        "{\"folder_code\":\"MANUSCRIPT\",\"episode_id\":\"" + episode + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episode_id").value(episode.toString()));

        mockMvc.perform(as(body(patch("/files/" + chapter + "/position"),
                        "{\"folder_code\":\"MANUSCRIPT\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episode_id").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void renamesDocumentsAndEpisodes() throws Exception {
        UUID document = createDocument("CHARACTER", "유중혁");
        UUID episode = createEpisode("1부");

        mockMvc.perform(as(body(patch("/files/" + document), "{\"title\":\"  김독자  \"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("김독자"));

        mockMvc.perform(as(body(patch("/episodes/" + episode), "{\"title\":\"2부\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("2부"));
    }

    @Test
    void refusesABlankOrOverlongTitle() throws Exception {
        UUID document = createDocument("CHARACTER", "유중혁");

        mockMvc.perform(as(body(patch("/files/" + document), "{\"title\":\"   \"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_TITLE"));

        mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"CHARACTER\",\"title\":\""
                                + "가".repeat(FileService.TITLE_MAX + 1) + "\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_TITLE"));
    }

    @Test
    void movesToTrashAndBack() throws Exception {
        UUID document = createDocument("CHARACTER", "유중혁");

        mockMvc.perform(as(post("/files/" + document + "/trash")))
                .andExpect(status().isNoContent());
        assertThat(documentTitlesInOrder()).isEmpty();

        mockMvc.perform(as(get("/projects/" + project + "/files/trash")))
                .andExpect(jsonPath("$.files.length()").value(1))
                .andExpect(jsonPath("$.files[0].folder_code").value("CHARACTER"))
                .andExpect(jsonPath("$.files[0].episode_name").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(as(post("/files/" + document + "/restore")))
                .andExpect(status().isOk());
        assertThat(documentTitlesInOrder()).containsExactly("유중혁");
    }

    @Test
    void freesTheTitleWhileTheFileIsInTheTrash() throws Exception {
        UUID trashed = createDocument("CHARACTER", "유중혁");
        mockMvc.perform(as(post("/files/" + trashed + "/trash"))).andExpect(status().isNoContent());

        createDocument("CHARACTER", "유중혁");

        mockMvc.perform(as(post("/files/" + trashed + "/restore")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_TITLE_TAKEN"));
    }

    @Test
    void returnsAChapterToTheManuscriptFolderWhenItsEpisodeIsGone() throws Exception {
        UUID episode = createEpisode("1부");
        UUID chapter = createChapter(episode, "1화");

        mockMvc.perform(as(post("/files/" + chapter + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/episodes/" + episode))).andExpect(status().isNoContent());

        mockMvc.perform(as(post("/files/" + chapter + "/restore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.folder_code").value("MANUSCRIPT"))
                .andExpect(jsonPath("$.episode_id").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void keepsChaptersWhenTheEpisodeFolderIsDeleted() throws Exception {
        UUID episode = createEpisode("1부");
        createChapter(episode, "1화");

        mockMvc.perform(as(delete("/episodes/" + episode))).andExpect(status().isNoContent());

        mockMvc.perform(as(get("/projects/" + project + "/files")))
                .andExpect(jsonPath("$.episodes.length()").value(0))
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].episode_id").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void deletesOnlyFromTheTrash() throws Exception {
        UUID document = createDocument("CHARACTER", "유중혁");

        mockMvc.perform(as(delete("/files/" + document)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FILE_NOT_TRASHED"));

        mockMvc.perform(as(post("/files/" + document + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/files/" + document))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/files/" + document)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void hidesAnotherOwnersFiles() throws Exception {
        UUID document = createDocument("CHARACTER", "유중혁");
        UUID episode = createEpisode("1부");

        mockMvc.perform(as(get("/projects/" + project + "/files"), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mockMvc.perform(as(body(patch("/files/" + document), "{\"title\":\"뺏기\"}"), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
        mockMvc.perform(as(delete("/episodes/" + episode), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }

    @Test
    void refusesAnUnknownKind() throws Exception {
        mockMvc.perform(as(body(post("/projects/" + project + "/files"),
                        "{\"kind\":\"section\",\"title\":\"사용자 섹션\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void answersNotFoundForAPathItDoesNotServe() throws Exception {
        // 이 그물이 없으면 Exception 핸들러가 NoResourceFoundException 까지 삼켜 500 이 된다.
        mockMvc.perform(as(get("/nope/at/all")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
