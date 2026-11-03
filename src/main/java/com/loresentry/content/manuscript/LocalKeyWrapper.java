package com.loresentry.content.manuscript;

import java.security.SecureRandom;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.logging.LogLayer;

@LogLayer("client")
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
        // 키 캐시에 없을 때만 불린다. 키(평문·감싼 것)는 남기지 않는다
        AppLogger.current().info("local data key generate call", LogField.projectId(projectId));
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return new Generated(key, Envelope.seal(masterKey, aad(projectId), key));
    }

    @Override
    public byte[] unwrap(UUID projectId, byte[] wrapped) {
        AppLogger.current().info("local data key unwrap call", LogField.projectId(projectId));
        return Envelope.open(masterKey, aad(projectId), wrapped);
    }

    private static String aad(UUID projectId) {
        return "lore-sentry:local-key:" + projectId;
    }
}
