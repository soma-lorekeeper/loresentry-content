package com.loresentry.content.web;

import java.util.UUID;

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
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-Lore-User-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(HEADER);
        if (header == null || header.isBlank()) {
            throw ApiException.unauthenticated(HEADER + " is missing");
        }

        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException exception) {
            throw ApiException.unauthenticated(HEADER + " is not a UUID");
        }
    }
}
