package com.loresentry.content.manuscript;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

final class KmsKeyWrapper implements KeyWrapper, AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final KmsClient kms;

    private final String keyId;

    KmsKeyWrapper(KmsClient kms, String keyId) {
        this.kms = kms;
        this.keyId = keyId;
    }

    @Override
    public Generated generate(UUID projectId) {
        GenerateDataKeyResponse response = kms.generateDataKey(request -> request
                .keyId(keyId)
                .keySpec(DataKeySpec.AES_256)
                .encryptionContext(context(projectId))
                .overrideConfiguration(config -> config.apiCallTimeout(TIMEOUT)));
        return new Generated(response.plaintext().asByteArray(), response.ciphertextBlob().asByteArray());
    }

    @Override
    public byte[] unwrap(UUID projectId, byte[] wrapped) {
        return kms.decrypt(request -> request
                        .keyId(keyId)
                        .ciphertextBlob(SdkBytes.fromByteArray(wrapped))
                        .encryptionContext(context(projectId))
                        .overrideConfiguration(config -> config.apiCallTimeout(TIMEOUT)))
                .plaintext()
                .asByteArray();
    }

    private static Map<String, String> context(UUID projectId) {
        return Map.of("project_id", projectId.toString());
    }

    @Override
    public void close() {
        kms.close();
    }
}
