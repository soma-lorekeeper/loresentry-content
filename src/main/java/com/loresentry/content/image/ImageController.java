package com.loresentry.content.image;

import java.util.UUID;

import com.loresentry.content.web.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ImageController {

    private final ImageService images;

    public ImageController(ImageService images) {
        this.images = images;
    }

    @PostMapping("/projects/{projectId}/images")
    public ResponseEntity<ImageDtos.Ticket> createTicket(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @RequestBody ImageDtos.TicketRequest request) {
        ImageDtos.Ticket ticket = images.createTicket(userId, projectId, request);
        return ResponseEntity
                .created(java.net.URI.create("/projects/" + projectId + "/images/" + ticket.imageId()))
                .body(ticket);
    }

    @PostMapping("/projects/{projectId}/images/{imageId}/complete")
    public ImageDtos.Image complete(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @PathVariable UUID imageId) {
        return images.complete(userId, projectId, imageId);
    }

    @GetMapping("/projects/{projectId}/images/{imageId}")
    public ImageDtos.Image get(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @PathVariable UUID imageId) {
        return images.get(userId, projectId, imageId);
    }
}
