package com.loresentry.content.web;

import java.net.URI;
import java.util.UUID;

import com.loresentry.content.media.ImageRecord;
import com.loresentry.content.media.ImageService;
import com.loresentry.content.media.UploadTicket;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/projects/{projectId}/images")
public class ImageController {

    public record CreateImageUploadRequest(String fileName, String contentType, Long sizeBytes) {
    }

    public record ImageResponse(
            UUID imageId,
            UUID projectId,
            String fileName,
            String key,
            String contentType,
            long sizeBytes,
            String status,
            String publicUrl) {
    }

    private final ImageService imageService;

    public ImageController(ImageService imageService) {
        this.imageService = imageService;
    }

    @PostMapping
    public ResponseEntity<UploadTicket> createUpload(
            @PathVariable UUID projectId,
            @RequestBody CreateImageUploadRequest request) {
        UploadTicket ticket = imageService.createUpload(
                projectId, request.fileName(), request.contentType(), request.sizeBytes());

        return ResponseEntity
                .created(URI.create("/projects/" + projectId + "/images/" + ticket.imageId()))
                .body(ticket);
    }

    @PostMapping("/{imageId}/complete")
    public ImageResponse complete(@PathVariable UUID projectId, @PathVariable UUID imageId) {
        return toResponse(imageService.complete(projectId, imageId));
    }

    @GetMapping("/{imageId}")
    public ImageResponse get(@PathVariable UUID projectId, @PathVariable UUID imageId) {
        return toResponse(imageService.get(projectId, imageId));
    }

    private ImageResponse toResponse(ImageRecord image) {
        return new ImageResponse(
                image.id(),
                image.projectId(),
                image.fileName(),
                image.s3Key(),
                image.contentType(),
                image.sizeBytes(),
                image.status().name(),
                imageService.publicUrl(image.s3Key()));
    }
}
