package com.loresentry.content.support;

import java.util.UUID;

import com.loresentry.content.web.CurrentUserArgumentResolver;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 HTTP 계층을 실제 PostgreSQL까지 관통해 검증한다. 컨트롤러만 떼어 놓으면 신원 헤더·오류 매핑·
 * 상태 전이가 통과해도 SQL이 틀릴 수 있고, repository만 보면 계약이 맞는지 알 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestSupport {

    /**
     * 컨테이너를 직접 띄운다. {@code @Container}는 테스트 클래스 하나가 끝나면 컨테이너를 멈추므로,
     * 이 클래스를 상속한 두 번째 테스트 클래스가 멈춘 DB에 붙으려 한다. JVM 이 끝나면
     * Testcontainers 의 정리 컨테이너가 치운다.
     */
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcClient jdbcClient;

    protected final JsonMapper json = JsonMapper.builder().build();

    protected final UUID owner = UUID.randomUUID();

    protected final UUID stranger = UUID.randomUUID();

    /** 프로젝트를 지우면 문서·에피소드가 CASCADE 로 함께 사라진다(V3). */
    @BeforeEach
    void clearProjects() {
        jdbcClient.sql("delete from projects").update();
    }

    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID user) {
        return request.header(CurrentUserArgumentResolver.HEADER, user.toString());
    }

    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return as(request, owner);
    }

    protected MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String payload) {
        return request.contentType(MediaType.APPLICATION_JSON).content(payload);
    }

    protected JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
