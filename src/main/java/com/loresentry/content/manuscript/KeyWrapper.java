package com.loresentry.content.manuscript;

import java.util.UUID;

public interface KeyWrapper {

    record Generated(byte[] plaintext, byte[] wrapped) {
    }

    Generated generate(UUID projectId);

    byte[] unwrap(UUID projectId, byte[] wrapped);
}
