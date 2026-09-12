package com.loresentry.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ContentApplicationTests {

    @Value("${spring.threads.virtual.enabled}")
    private boolean virtualThreadsEnabled;

    @Test
    void contextLoads() {
    }

    @Test
    void virtualThreadsAreEnabled() {
        assertThat(virtualThreadsEnabled).isTrue();
    }
}
