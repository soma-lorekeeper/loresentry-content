package com.loresentry.content.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * authentication 서비스와 같은 엄격함이다. 알 수 없는 필드를 조용히 버리면 클라이언트의 오타가
 * "저장은 됐는데 값이 안 바뀐다"로 나타난다.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictRequestJson() {
        return builder -> builder
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
