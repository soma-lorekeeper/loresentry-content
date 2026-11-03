package com.loresentry.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loresentry.content.support.ApiTestSupport;

import org.junit.jupiter.api.Test;

/**
 * DB 연결 확인 본문에 DB 사용자 이름·버전이 없는지 확인한다. gateway /health/db 는 이 본문을 모아 로그인한 사용자에게도 돌려준다.
 */
class DbHealthControllerTest extends ApiTestSupport {

    @Test
    void theBodyHasNoUserNameOrVersion() throws Exception {
        var body = json.readTree(mockMvc.perform(get("/health/db"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("status", "service", "database");
        assertThat(body.get("status").asString()).isEqualTo("ok");
    }
}
