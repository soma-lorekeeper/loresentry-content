package com.loresentry.content.manuscript;

import java.security.SecureRandom;
import java.util.UUID;

final class LocalKeyWrapper implements KeyWrapper {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] masterKey;

    LocalKeyWrapper(byte[] masterKey) {
        if (masterKey.length != 32) {
            throw new IllegalArgumentException("Local master key must be 32 bytes");
        }
        this.masterKey = masterKey.clone();
    }

    @Override
    public Generated generate(UUID projectId) {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return new Generated(key, Envelope.seal(masterKey, aad(projectId), key));
    }

    @Override
    public byte[] unwrap(UUID projectId, byte[] wrapped) {
        return Envelope.open(masterKey, aad(projectId), wrapped);
    }

    private static String aad(UUID projectId) {
        return "lore-sentry:local-key:" + projectId;
    }
}
