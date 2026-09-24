package com.loresentry.content.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.media.InvalidUploadRequestException;
import com.loresentry.content.media.MediaStorageService;
import com.loresentry.content.media.ObjectNotUploadedException;
import com.loresentry.content.media.StoredObject;
import com.loresentry.content.media.UploadTicket;
import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * S3 자체는 {@code MediaStorageServiceTest} 가 실제 서명기로 검증한다. 여기서는 엔드포인트와
 * {@code image} 행의 상태 전이를 본다 — 스토리지를 흉내 내면 테스트가 AWS 없이도 돈다.
 */
class ImageApiTest extends ApiTestSupport {

    private static final String KEY = "projects/p/images/abc.png";

    @MockitoBean
    private MediaStorageService storage;

    private UUID project;

    @BeforeEach
    void seed() throws Exception {
        MvcResult created = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"삽화가 있는 원고\"}")))
                .andReturn();
        this.project = UUID.fromString(read(created).get("id").stringValue());

        given(storage.createImageUploadTicket(any(), anyString(), anyLong()))
                .willReturn(new UploadTicket(KEY, "https://s3.example/put", "PUT",
                        Map.of("Content-Type", "image/png", "Content-Length", "1234"),
                        Instant.parse("2026-09-24T00:05:00Z"),
                        "https://media.loresentry.com/" + KEY));
        given(storage.publicUrl(KEY)).willReturn("https://media.loresentry.com/" + KEY);
    }

    private UUID ticket() throws Exception {
        MvcResult result = mockMvc.perform(as(body(post("/projects/" + project + "/images"),
                        "{\"file_name\":\"cover.png\",\"content_type\":\"image/png\",\"size_bytes\":1234}")))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(read(result).get("image_id").stringValue());
    }

    @Test
    void handsOutEverythingTheBrowserNeedsToUploadDirectly() throws Exception {
        mockMvc.perform(as(body(post("/projects/" + project + "/images"),
                        "{\"file_name\":\"cover.png\",\"content_type\":\"image/png\",\"size_bytes\":1234}")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.upload_url").value("https://s3.example/put"))
                .andExpect(jsonPath("$.method").value("PUT"))
                .andExpect(jsonPath("$.headers['Content-Type']").value("image/png"))
                .andExpect(jsonPath("$.expires_at").exists())
                .andExpect(jsonPath("$.key").value(KEY));
    }

    @Test
    void recordsTheTicketAsPendingUntilTheUploadIsConfirmed() throws Exception {
        UUID imageId = ticket();

        mockMvc.perform(as(get("/projects/" + project + "/images/" + imageId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.committed_at").value(org.hamcrest.Matchers.nullValue()))
                // PENDING 의 주소를 주면 클라이언트가 문서에 넣고, 업로드가 실패하면 깨진 이미지가 남는다.
                .andExpect(jsonPath("$.public_url").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void confirmsTheUploadAgainstStorageRatherThanTrustingTheClient() throws Exception {
        UUID imageId = ticket();
        given(storage.verifyUploaded(KEY, 1234L)).willReturn(new StoredObject(KEY, "image/png", 1234));

        mockMvc.perform(as(post("/projects/" + project + "/images/" + imageId + "/complete")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.committed_at").exists())
                .andExpect(jsonPath("$.public_url").value("https://media.loresentry.com/" + KEY));

        verify(storage).verifyUploaded(KEY, 1234L);
    }

    @Test
    void refusesToCommitAnObjectThatIsNotThere() throws Exception {
        UUID imageId = ticket();
        willThrow(new ObjectNotUploadedException("missing"))
                .given(storage).verifyUploaded(anyString(), anyLong());

        mockMvc.perform(as(post("/projects/" + project + "/images/" + imageId + "/complete")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OBJECT_NOT_UPLOADED"));

        // 행은 PENDING 으로 남아 재시도할 수 있다.
        mockMvc.perform(as(get("/projects/" + project + "/images/" + imageId)))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void treatsASecondCompleteAsTheSameOutcome() throws Exception {
        UUID imageId = ticket();
        given(storage.verifyUploaded(KEY, 1234L)).willReturn(new StoredObject(KEY, "image/png", 1234));

        mockMvc.perform(as(post("/projects/" + project + "/images/" + imageId + "/complete")))
                .andExpect(status().isOk());
        MvcResult first = mockMvc.perform(as(get("/projects/" + project + "/images/" + imageId)))
                .andReturn();
        String committedAt = read(first).get("committed_at").stringValue();

        // 응답을 받지 못한 클라이언트가 다시 부른다. 확인을 다시 하지 않고 같은 결과를 준다.
        mockMvc.perform(as(post("/projects/" + project + "/images/" + imageId + "/complete")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.committed_at").value(committedAt));

        verify(storage, org.mockito.Mockito.times(1)).verifyUploaded(anyString(), anyLong());
    }

    @Test
    void refusesATypeOrSizeStorageWillNotSign() throws Exception {
        willThrow(new InvalidUploadRequestException("unsupported"))
                .given(storage).createImageUploadTicket(any(), anyString(), anyLong());

        mockMvc.perform(as(body(post("/projects/" + project + "/images"),
                        "{\"file_name\":\"x.svg\",\"content_type\":\"image/svg+xml\",\"size_bytes\":10}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UPLOAD_REQUEST"));
    }

    @Test
    void refusesARequestMissingTheFieldsThatGoIntoTheSignature() throws Exception {
        mockMvc.perform(as(body(post("/projects/" + project + "/images"),
                        "{\"file_name\":\"cover.png\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(storage, never()).createImageUploadTicket(any(), anyString(), anyLong());
    }

    @Test
    void hidesImagesOfAnotherOwnersProject() throws Exception {
        UUID imageId = ticket();

        mockMvc.perform(as(get("/projects/" + project + "/images/" + imageId), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        mockMvc.perform(as(body(post("/projects/" + project + "/images"),
                        "{\"content_type\":\"image/png\",\"size_bytes\":1}"), stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void doesNotServeAnImageThroughAnotherProjectOfTheSameOwner() throws Exception {
        UUID imageId = ticket();
        MvcResult other = mockMvc.perform(as(body(post("/projects"), "{\"name\":\"다른 프로젝트\"}")))
                .andReturn();
        UUID otherProject = UUID.fromString(read(other).get("id").stringValue());

        // 이미지 id 만으로 찾으면 경로의 프로젝트와 무관하게 보인다.
        mockMvc.perform(as(get("/projects/" + otherProject + "/images/" + imageId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
    }

    @Test
    void answersNotFoundForAnUnknownImage() throws Exception {
        mockMvc.perform(as(get("/projects/" + project + "/images/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
    }

    @Test
    void deletesImageRowsWithTheProject() throws Exception {
        ticket();
        mockMvc.perform(as(post("/projects/" + project + "/trash"))).andExpect(status().isNoContent());
        mockMvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/projects/" + project)))
                .andExpect(status().isNoContent());

        assertThat(jdbcClient.sql("select count(*) from image where project_id = :project")
                .param("project", project).query(Long.class).single()).isZero();
    }
}
