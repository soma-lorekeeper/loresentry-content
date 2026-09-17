package com.loresentry.content.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.media.ImageNotFoundException;
import com.loresentry.content.media.ImageRecord;
import com.loresentry.content.media.ImageService;
import com.loresentry.content.media.ImageStatus;
import com.loresentry.content.media.InvalidUploadRequestException;
import com.loresentry.content.media.ObjectNotUploadedException;
import com.loresentry.content.media.UploadTicket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ImageController.class)
class ImageControllerTest {

    private static final UUID PROJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID IMAGE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImageService imageService;

    @Test
    void createUploadReturnsCreatedWithTheTicket() throws Exception {
        String key = "projects/" + PROJECT + "/images/" + IMAGE + ".png";
        given(imageService.createUpload(PROJECT, "cover.png", "image/png", 1234L))
                .willReturn(new UploadTicket(IMAGE, key, "https://bucket.s3.amazonaws.com/" + key + "?sig", "PUT",
                        Map.of("Content-Type", "image/png", "Content-Length", "1234"),
                        Instant.parse("2026-09-18T00:05:00Z"), "https://media.test.invalid/" + key));

        mockMvc.perform(post("/projects/{projectId}/images", PROJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"cover.png\",\"contentType\":\"image/png\",\"sizeBytes\":1234}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/projects/" + PROJECT + "/images/" + IMAGE))
                .andExpect(jsonPath("$.imageId").value(IMAGE.toString()))
                .andExpect(jsonPath("$.method").value("PUT"))
                .andExpect(jsonPath("$.uploadUrl").value("https://bucket.s3.amazonaws.com/" + key + "?sig"))
                .andExpect(jsonPath("$.headers.Content-Type").value("image/png"))
                .andExpect(jsonPath("$.headers.Content-Length").value("1234"))
                .andExpect(jsonPath("$.publicUrl").value("https://media.test.invalid/" + key));
    }

    @Test
    void invalidUploadRequestIsBadRequest() throws Exception {
        given(imageService.createUpload(eq(PROJECT), any(), any(), any()))
                .willThrow(new InvalidUploadRequestException("contentType image/svg+xml is not allowed"));

        mockMvc.perform(post("/projects/{projectId}/images", PROJECT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"a.svg\",\"contentType\":\"image/svg+xml\",\"sizeBytes\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_upload_request"))
                .andExpect(jsonPath("$.message").value("contentType image/svg+xml is not allowed"));
    }

    @Test
    void completeReturnsTheCommittedImage() throws Exception {
        String key = "projects/" + PROJECT + "/images/" + IMAGE + ".png";
        given(imageService.complete(PROJECT, IMAGE))
                .willReturn(new ImageRecord(IMAGE, PROJECT, "cover.png", key, "image/png", 1234L,
                        ImageStatus.COMMITTED, OffsetDateTime.now(), OffsetDateTime.now()));
        given(imageService.publicUrl(key)).willReturn("https://media.test.invalid/" + key);

        mockMvc.perform(post("/projects/{projectId}/images/{imageId}/complete", PROJECT, IMAGE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageId").value(IMAGE.toString()))
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.publicUrl").value("https://media.test.invalid/" + key));
    }

    @Test
    void completeBeforeUploadIsConflict() throws Exception {
        given(imageService.complete(PROJECT, IMAGE))
                .willThrow(new ObjectNotUploadedException("object has not been uploaded"));

        mockMvc.perform(post("/projects/{projectId}/images/{imageId}/complete", PROJECT, IMAGE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("object_not_uploaded"));
    }

    @Test
    void unknownImageIsNotFound() throws Exception {
        given(imageService.get(PROJECT, IMAGE)).willThrow(new ImageNotFoundException(PROJECT, IMAGE));

        mockMvc.perform(get("/projects/{projectId}/images/{imageId}", PROJECT, IMAGE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("image_not_found"));
    }
}
