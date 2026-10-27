package com.loresentry.content.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.media.MediaStorageService;
import com.loresentry.content.media.StoredObject;
import com.loresentry.content.media.UploadTicket;
import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 문서의 대표 이미지. 업로드 자체는 {@code ImageApiTest} 가 보고, 여기서는 문서에 붙이고 떼는 것과 그때 이전
 * 이미지가 정리되는지를 본다. 스토리지는 흉내 낸다 — 티켓마다 다른 키를 준다.
 */
class DocumentImageApiTest extends ApiTestSupport {

    private static final String MEDIA = "https://media.loresentry.com/";

    @MockitoBean
    private MediaStorageService storage;

    private UUID project;

    private UUID character;

    @BeforeEach
    void seed() throws Exception {
        this.project = createProject("유리 정원의 기록");
        this.character = createDocument(project, "CHARACTER", "레나 아르벨");

        given(storage.createImageUploadTicket(any(), anyString(), anyLong())).willAnswer(call -> {
            String key = "projects/" + call.getArgument(0) + "/images/" + UUID.randomUUID() + ".webp";
            return new UploadTicket(key, "https://s3.example/put", "PUT",
                    Map.of("Content-Type", "image/webp"), Instant.parse("2026-10-27T00:05:00Z"), MEDIA + key);
        });
        given(storage.publicUrl(anyString())).willAnswer(call -> MEDIA + call.getArgument(0));
        given(storage.verifyUploaded(anyString(), anyLong()))
                .willAnswer(call -> new StoredObject(call.getArgument(0), "image/webp", call.getArgument(1)));
    }

    private UUID createProject(String name) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"" + name + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private UUID createDocument(UUID projectId, String folderCode, String title) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + projectId + "/files"),
                        "{\"kind\":\"document\",\"folder_code\":\"" + folderCode + "\",\"title\":\"" + title + "\"}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("id").stringValue());
    }

    private UUID ticket(UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + projectId + "/images"),
                        "{\"file_name\":\"lena.webp\",\"content_type\":\"image/webp\",\"size_bytes\":2048}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("image_id").stringValue());
    }

    private UUID uploaded(UUID projectId) throws Exception {
        UUID imageId = ticket(projectId);
        mockMvc.perform(as(post("/projects/" + projectId + "/images/" + imageId + "/complete")))
                .andExpect(status().isOk());
        return imageId;
    }

    private String keyOf(UUID imageId) {
        return jdbcClient.sql("select s3_key from image where id = :id").param("id", imageId)
                .query(String.class).single();
    }

    private boolean imageRowExists(UUID imageId) {
        return jdbcClient.sql("select 1 from image where id = :id").param("id", imageId)
                .query(Integer.class).optional().isPresent();
    }

    private MvcResult setImage(UUID fileId, UUID imageId) throws Exception {
        return mockMvc.perform(as(body(put("/files/" + fileId + "/image"), "{\"image_id\":\"" + imageId + "\"}")))
                .andReturn();
    }

    @Test
    void showsTheImageInTheTreeAndOnTheRelationshipMap() throws Exception {
        UUID imageId = uploaded(project);
        String url = MEDIA + keyOf(imageId);

        mockMvc.perform(as(body(put("/files/" + character + "/image"), "{\"image_id\":\"" + imageId + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(character.toString()))
                .andExpect(jsonPath("$.image_url").value(url));

        mockMvc.perform(as(get("/projects/" + project + "/files")))
                .andExpect(jsonPath("$.documents[0].image_url").value(url));
        mockMvc.perform(as(get("/projects/" + project + "/graph")))
                .andExpect(jsonPath("$.nodes[0].image_url").value(url));
    }

    @Test
    void leavesTheRevisionAloneSoAnOpenEditorDoesNotConflict() throws Exception {
        long before = jdbcClient.sql("select revision_no from document where id = :id").param("id", character)
                .query(Long.class).single();

        setImage(character, uploaded(project));

        long after = jdbcClient.sql("select revision_no from document where id = :id").param("id", character)
                .query(Long.class).single();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void sendsNoAddressForADocumentWithoutAnImage() throws Exception {
        mockMvc.perform(as(get("/projects/" + project + "/files")))
                .andExpect(jsonPath("$.documents[0].image_url").value(nullValue()));
        mockMvc.perform(as(get("/projects/" + project + "/graph")))
                .andExpect(jsonPath("$.nodes[0].image_url").value(nullValue()));
    }

    @Test
    void refusesAnImageWhoseUploadIsNotConfirmed() throws Exception {
        UUID pending = ticket(project);

        mockMvc.perform(as(body(put("/files/" + character + "/image"), "{\"image_id\":\"" + pending + "\"}")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OBJECT_NOT_UPLOADED"));
    }

    @Test
    void refusesAnImageFromAnotherProject() throws Exception {
        UUID other = createProject("안개 항구 살인사건");
        UUID foreign = uploaded(other);

        mockMvc.perform(as(body(put("/files/" + character + "/image"), "{\"image_id\":\"" + foreign + "\"}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
    }

    @Test
    void refusesAnImageAnotherDocumentAlreadyUses() throws Exception {
        UUID imageId = uploaded(project);
        setImage(character, imageId);
        UUID place = createDocument(project, "LOCATION", "북쪽 온실");

        mockMvc.perform(as(body(put("/files/" + place + "/image"), "{\"image_id\":\"" + imageId + "\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void deletesThePreviousImageWhenReplaced() throws Exception {
        UUID first = uploaded(project);
        String firstKey = keyOf(first);
        setImage(character, first);

        UUID second = uploaded(project);
        mockMvc.perform(as(body(put("/files/" + character + "/image"), "{\"image_id\":\"" + second + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image_url").value(MEDIA + keyOf(second)));

        assertThat(imageRowExists(first)).isFalse();
        verify(storage).delete(firstKey);
    }

    @Test
    void keepsTheImageWhenTheSameOneIsSetAgain() throws Exception {
        UUID imageId = uploaded(project);
        setImage(character, imageId);

        mockMvc.perform(as(body(put("/files/" + character + "/image"), "{\"image_id\":\"" + imageId + "\"}")))
                .andExpect(status().isOk());

        assertThat(imageRowExists(imageId)).isTrue();
        verify(storage, never()).delete(anyString());
    }

    @Test
    void removesTheImageAndItsObject() throws Exception {
        UUID imageId = uploaded(project);
        String key = keyOf(imageId);
        setImage(character, imageId);

        mockMvc.perform(as(delete("/files/" + character + "/image")))
                .andExpect(status().isNoContent());

        mockMvc.perform(as(get("/projects/" + project + "/files")))
                .andExpect(jsonPath("$.documents[0].image_url").value(nullValue()));
        assertThat(imageRowExists(imageId)).isFalse();
        verify(storage).delete(key);

        // 다시 불러도 같은 결과다.
        mockMvc.perform(as(delete("/files/" + character + "/image")))
                .andExpect(status().isNoContent());
    }

    @Test
    void refusesToChangeTheImageOfALockedDocument() throws Exception {
        UUID imageId = uploaded(project);
        mockMvc.perform(as(body(put("/files/" + character + "/lock"), "{\"locked\":true}")))
                .andExpect(status().isOk());

        mockMvc.perform(as(body(put("/files/" + character + "/image"), "{\"image_id\":\"" + imageId + "\"}")))
                .andExpect(jsonPath("$.code").value("DOCUMENT_LOCKED"));
        mockMvc.perform(as(delete("/files/" + character + "/image")))
                .andExpect(jsonPath("$.code").value("DOCUMENT_LOCKED"));
    }

    @Test
    void deletesTheImageWithTheDocument() throws Exception {
        UUID imageId = uploaded(project);
        String key = keyOf(imageId);
        setImage(character, imageId);

        mockMvc.perform(as(post("/files/" + character + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(delete("/files/" + character))).andExpect(status().isNoContent());

        assertThat(imageRowExists(imageId)).isFalse();
        verify(storage).delete(key);
    }

    @Test
    void keepsTheImageWhileTheDocumentIsInTheTrash() throws Exception {
        UUID imageId = uploaded(project);
        setImage(character, imageId);

        mockMvc.perform(as(post("/files/" + character + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(post("/files/" + character + "/restore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image_url").value(MEDIA + keyOf(imageId)));
    }

    @Test
    void hidesAnotherWritersDocument() throws Exception {
        UUID imageId = uploaded(project);

        mockMvc.perform(as(body(put("/files/" + character + "/image"), "{\"image_id\":\"" + imageId + "\"}"),
                        stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
    }
}
