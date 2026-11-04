package com.loresentry.content.logging;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 로그·trace 배선. LOG_SCHEMA.md 5·7절.
 *
 * <ul>
 *   <li>요청 시작점·접근 로그 필터(HIGHEST_PRECEDENCE + 2). 서버 span은 EDOT agent의 Tomcat 계측이 필터보다 먼저 연다.</li>
 *   <li>헬스체크도 다른 요청처럼 span과 로그를 남긴다. 정상 헬스체크의 info 줄은 Logstash가 tags: health로 버린다.</li>
 *   <li>Boot 기본 오류 응답(/error)에도 trace_id를 싣는다.</li>
 * </ul>
 *
 * <p>Kafka 레코드의 trace는 EDOT agent의 Spring Kafka 계측이 잇고, 스케줄 작업 1회의 trace는 Spring Scheduling 계측이 연다.
 * 둘 다 여기서 배선할 것이 없다.
 */
@Configuration(proxyBeanMethods = false)
public class LoggingConfiguration {

    @Bean
    public FilterRegistrationBean<RequestLogFilter> requestLogFilter(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings) {
        // 컨트롤러 매핑은 MVC 설정 bean이다(다른 RequestMappingHandlerMapping과 헷갈리지 않게 이름으로 고른다).
        // 필터를 만들 때가 아니라 요청마다 꺼내므로 bean 생성 순서에 기대지 않는다
        var registration = new FilterRegistrationBean<>(new RequestLogFilter(mappings));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    /** Boot 기본 오류 본문({timestamp, status, error, path})에 trace_id를 더한다. 값은 RequestLogFilter가 요청 속성에 남긴다. */
    @Bean
    public DefaultErrorAttributes errorAttributes() {
        return new DefaultErrorAttributes() {
            @Override
            public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
                Map<String, Object> attributes = super.getErrorAttributes(request, options);
                Object traceId = request.getAttribute(RequestLogFilter.TRACE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
                if (traceId != null) attributes.put("trace_id", traceId);
                return attributes;
            }
        };
    }
}
