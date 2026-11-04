package com.loresentry.content.logging;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * gateway가 부른 요청 하나의 시작점. 요청마다 {@link AppLogger}를 만들어 바인딩하고 접근 로그를 남긴다.
 *
 * <p>순서: EDOT agent의 Tomcat 계측이 필터보다 먼저 gateway가 보낸 traceparent를 이어 서버 span을 열고, 이 필터(+2)가 그 안에서
 * 돈다. 그래서 이 요청의 모든 줄이 gateway 줄과 같은 trace.id를 갖는다.
 *
 * <p>하는 일
 * <ol>
 *   <li>지금 span의 traceId·spanId와 gateway가 확인해 보낸 사용자(X-User-Id)로 로그 객체를 만든다. 요청 하나의 모든 줄에
 *       이 서비스가 받은 경로({@code url.path}), 경로 템플릿({@code http.route}), 메서드({@code http.request.method})를 붙인다.
 *       경로 템플릿은 Spring MVC가 나중에 고를 것을 요청 시작 때 미리 찾는다. 이 값들은 MDC에도 들어가 같은 스레드의 프레임워크
 *       줄(Spring, Hibernate 등)에도 붙는다.</li>
 *   <li>"request received"와 "request completed"(상태·걸린 시간)를 남긴다. 헬스체크도 같고, 그 요청의 모든 줄에
 *       {@code tags: health}가 붙어 정상(info) 줄은 Logstash가 버린다. 결과 줄에는 예외 처리기가
 *       {@link AppLogger#addToOutcome(LogField...)}로 맡긴 실패 이유(error.type)도 붙인다. 실패를 따로 찍지 않으므로 요청 하나의
 *       결과는 늘 한 줄이다.</li>
 * </ol>
 *
 * <p>쿼리스트링은 남기지 않는다(검색어). 경로만 {@code url.path}에 넣는다.
 */
public final class RequestLogFilter extends OncePerRequestFilter {
    /** Boot 기본 오류 응답(/error)에도 trace_id를 싣기 위해 요청 속성에 남기는 이름. */
    public static final String TRACE_ATTRIBUTE = RequestLogFilter.class.getName() + ".traceId";
    /** gateway가 세션을 확인한 뒤 넣는 사용자 헤더. 컨트롤러의 @CurrentUser와 같은 헤더다. */
    private static final String USER_HEADER = "X-User-Id";

    /** 경로 템플릿을 미리 찾는 데 쓰는 컨트롤러 매핑. MVC가 없는 테스트 문맥에서는 비어 있고, 그때는 http.route를 붙이지 않는다. */
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    public RequestLogFilter(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        AppLogger logger = TraceContext.newLogger().withUser(user(request))
                .with(LogField.urlPath(path), LogField.route(route(request)), LogField.httpMethod(request.getMethod()));
        // 헬스체크 흐름의 모든 줄에 tags: health를 붙인다. 정상(info) 줄은 Logstash가 버리고, 실패한 헬스체크의 warn·error는 남는다
        if (health(path)) logger = logger.withTag("health");
        if (logger.traceId() != null) request.setAttribute(TRACE_ATTRIBUTE, logger.traceId());
        long started = System.nanoTime();
        try (var bound = AppLogger.bind(logger)) {
            logger.info("request received");
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException | RuntimeException failure) {
                // 컨트롤러 예외 처리기가 잡지 못한 예외. 이 뒤 Tomcat이 /error로 500을 쓴다.
                // 예외 메시지에는 요청 값·SQL이 섞일 수 있어, 예외 객체 대신 클래스 이름과 메시지 없는 스택만 남긴다.
                // 같은 이름은 먼저 넣은 값이 남으므로(EcsLogFormatter) error.type을 맡겨진 결과 필드보다 앞에 둔다
                logger.error("request failed with an unhandled exception",
                        concat(new LogField[]{LogField.errorType(failure.getClass().getName()), LogField.safeStackTrace(failure),
                                LogField.statusCode(500), LogField.duration(elapsed(started))}, logger.outcomeFields()));
                throw failure;
            }
            completed(logger, response.getStatus(), started);
        }
    }

    /**
     * 결과 줄. 응답 상태로 레벨을 정한다: 5xx error, 4xx warn, 그 외 info. 2xx 헬스체크 줄은 Logstash가 버린다.
     * 기본 필드(상태, 걸린 시간) 뒤에 이 요청에 맡겨진 결과 필드를 그대로 붙인다. url.path·http.route·http.request.method는
     * 문맥 필드로 이미 붙어 있다.
     */
    private static void completed(AppLogger logger, int status, long started) {
        LogField[] fields = concat(new LogField[]{LogField.statusCode(status), LogField.duration(elapsed(started))},
                logger.outcomeFields());
        if (status >= 500) logger.error("request completed", fields);
        else if (status >= 400) logger.warn("request completed", fields);
        else logger.info("request completed", fields);
    }

    /**
     * Spring MVC가 이 요청에 고를 경로 템플릿(예: /projects/{projectId}/files/{fileId}). DispatcherServlet이 나중에 같은 매핑으로
     * 똑같이 고르므로 결과가 같다.
     *
     * <p>매칭은 요청 속성(파싱한 경로, 고른 handler·템플릿 등)을 남긴다. 뒤의 DispatcherServlet이 보는 상태가 바뀌지 않도록
     * 매칭 전 속성을 기억해 두었다가 그대로 되돌린다. 맞는 경로가 없거나(404) 매칭 중 예외(405 등)가 나면 null이다.
     */
    private String route(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = mappings.getIfAvailable();
        if (mapping == null) return null;
        Map<String, Object> before = new HashMap<>();
        for (String name : Collections.list(request.getAttributeNames())) before.put(name, request.getAttribute(name));
        try {
            // 경로 패턴 매칭은 파싱한 요청 경로를 요청 속성에서 읽는다. DispatcherServlet도 나중에 다시 파싱한다
            ServletRequestPathUtils.parseAndCache(request);
            if (mapping.getHandler(request) == null) return null;
            return request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String pattern ? pattern : null;
        } catch (Exception noMatch) {
            return null;
        } finally {
            // 매칭이 새로 만든 속성은 지우고, 바꾸거나 지운 속성은 원래 값으로 되돌린다
            for (String name : Collections.list(request.getAttributeNames())) {
                if (!before.containsKey(name)) request.removeAttribute(name);
            }
            before.forEach(request::setAttribute);
        }
    }

    /**
     * gateway가 보낸 사용자. 값이 하나이고 표준 표기 UUID일 때만 쓴다. 틀린 값은 컨트롤러가 400으로 거절하므로 로그에는 남기지 않는다.
     * 헤더가 없는 요청(헬스체크 등)은 null이다.
     */
    private static UUID user(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders(USER_HEADER));
        if (values.size() != 1 || values.getFirst().length() != 36) return null;
        try {
            return UUID.fromString(values.getFirst());
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    private static boolean health(String path) {
        return "/health".equals(path) || path.startsWith("/health/");
    }

    private static LogField[] concat(LogField[] first, LogField[] second) {
        LogField[] all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
