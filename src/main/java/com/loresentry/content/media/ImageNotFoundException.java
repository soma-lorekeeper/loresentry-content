package com.loresentry.content.media;

import java.util.UUID;

public class ImageNotFoundException extends RuntimeException {

    public ImageNotFoundException(UUID projectId, UUID imageId) {
        super("image " + imageId + " not found in project " + projectId);
    }
}
