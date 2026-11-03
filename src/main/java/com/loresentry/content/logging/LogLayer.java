package com.loresentry.content.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 클래스 이름 끝으로 레이어를 정할 수 없는 클래스에 log.layer 값을 직접 지정한다(LOG_SCHEMA.md 5절 "레이어와 위치").
 * 예: 스케줄 작업 {@code OutboxRelay}는 {@code @LogLayer("scheduler")}.
 *
 * <p>이름 끝 규칙(Controller, Service, Repository·Store, Client, Listener·Consumer, Publisher, Filter, Handler, Interceptor)보다
 * 먼저 본다. 안쪽 클래스(익명 클래스 포함)에 없으면 바깥 클래스의 값을 쓴다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LogLayer {
    /** log.layer 값. LOG_SCHEMA.md 5절의 레이어 이름 중 하나를 쓴다. */
    String value();
}
