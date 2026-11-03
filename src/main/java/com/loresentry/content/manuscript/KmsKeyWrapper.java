package com.loresentry.content.manuscript;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;
import com.loresentry.content.logging.LogLayer;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

@LogLayer("client")
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
        // 키 캐시에 없을 때만 불린다. 그래서 이 줄이 있으면 KMS 를 실제로 부른 것이다. 키(평문·감싼 것)는 남기지 않는다
        AppLogger.current().info("kms data key generate call", LogField.projectId(projectId));
        GenerateDataKeyResponse response = kms.generateDataKey(request -> request
                .keyId(keyId)
                .keySpec(DataKeySpec.AES_256)
                .encryptionContext(context(projectId))
                .overrideConfiguration(config -> config.apiCallTimeout(TIMEOUT)));
        return new Generated(response.plaintext().asByteArray(), response.ciphertextBlob().asByteArray());
    }

    @Override
    public byte[] unwrap(UUID projectId, byte[] wrapped) {
        // 키 캐시에 없을 때만 불린다. 감싼 키는 남기지 않는다
        AppLogger.current().info("kms data key unwrap call", LogField.projectId(projectId));
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
        AppLogger.current().info("kms client close");
        kms.close();
    }
}
