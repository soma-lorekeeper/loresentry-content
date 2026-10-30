package com.loresentry.content.manuscript;

import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

@Configuration
class ManuscriptConfig {

    @Bean
    KeyWrapper keyWrapper(@Value("${manuscript.key-id:}") String keyId,
            @Value("${manuscript.region:ap-northeast-2}") String region,
            @Value("${manuscript.local-master-key:}") String localMasterKey) {
        if (!keyId.isBlank()) {
            return new KmsKeyWrapper(KmsClient.builder().region(Region.of(region)).build(), keyId);
        }
        if (!localMasterKey.isBlank()) {
            return new LocalKeyWrapper(HexFormat.of().parseHex(localMasterKey));
        }
        throw new IllegalStateException("manuscript.key-id is not set");
    }
}
