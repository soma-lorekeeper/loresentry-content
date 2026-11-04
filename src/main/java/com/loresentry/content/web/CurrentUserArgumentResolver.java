package com.loresentry.content.web;

import java.util.Set;
import java.util.UUID;

import com.loresentry.content.logging.AppLogger;
import com.loresentry.content.logging.LogField;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * content는 이 헤더를 검증하지 않고 신뢰한다. 검증은 gateway 한 곳의 책임이다 —
 * 서비스마다 토큰을 검증하면 인증 로직이 네 곳으로 복제된다.
 *
 * <p>gateway는 클라이언트가 보낸 같은 이름의 헤더를 먼저 제거한 뒤 자기가 검증한 값을 넣어야 한다.
 * 그 한 줄이 빠지면 누구나 남의 사용자 id를 사칭할 수 있다.
 *
 * <p>헤더 이름과 거절 규칙은 authentication 서비스의 {@code AccountController}와 같다. gateway가
 * 서비스마다 다른 이름으로 신원을 실어야 할 이유가 없다.
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-User-Id";

    /** 헤더를 400 으로 거절한 이유(로그 전용). 값은 남기지 않는다 — gateway 가 넣은 사용자 id 이거나 위조 시도다. */
    private static final Set<String> REJECT_REASONS = Set.of("MULTIPLE", "NOT_UUID", "NON_CANONICAL");

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String[] values = webRequest.getHeaderValues(HEADER);
        if (values == null || values.length == 0) {
            throw new ContentFailure(ContentFailure.Reason.USER_CONTEXT_REQUIRED);
        }
        // 값이 둘 이상이면 어느 것이 gateway의 것인지 알 수 없다. 고르지 않고 거절한다.
        if (values.length != 1) {
            AppLogger.current().warn("user header rejected", LogField.errorType("INVALID_REQUEST"),
                    LogField.resultCode("reason", "MULTIPLE", REJECT_REASONS), LogField.result("values", values.length));
            throw invalidRequest();
        }

        try {
            String text = values[0];
            UUID id = UUID.fromString(text);
            // UUID.fromString은 비정규 표기도 받아들인다. 왕복해서 같아야만 통과시킨다.
            if (!id.toString().equalsIgnoreCase(text)) {
                AppLogger.current().warn("user header rejected", LogField.errorType("INVALID_REQUEST"),
                        LogField.resultCode("reason", "NON_CANONICAL", REJECT_REASONS), LogField.result("values", 1));
                throw invalidRequest();
            }
            return id;
        } catch (IllegalArgumentException invalid) {
            AppLogger.current().warn("user header rejected", LogField.errorType("INVALID_REQUEST"),
                    LogField.resultCode("reason", "NOT_UUID", REJECT_REASONS), LogField.result("values", 1));
            throw invalidRequest();
        }
    }

    private static ContentFailure invalidRequest() {
        return new ContentFailure(ContentFailure.Reason.INVALID_REQUEST);
    }
}
