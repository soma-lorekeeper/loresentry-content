package com.loresentry.content.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 레이어 클래스의 private이 아닌 메서드인데도 진입 로그를 남기지 않는 메서드에 붙인다(LOG_SCHEMA.md 5절 진입 로그 규칙의 예외).
 * LoggingArchitectureTest가 이 annotation이 붙은 메서드는 검사하지 않는다.
 *
 * <p>예외는 두 가지뿐이다.
 * <ul>
 *   <li>값을 돌려주기만 하는 getter처럼, 줄을 남겨도 흐름에 새 정보가 없고 한 요청 안에서 여러 번 불려 같은 줄만 쌓이는 메서드</li>
 *   <li>짧은 주기로 도는 폴링의 실행 메서드(1초 outbox relay 등). 처리할 일이 있을 때만 시작·결과 줄을 남긴다</li>
 * </ul>
 * 예: {@code OutboxRelay.run()}은 1초마다 돌아, 보낼 행이 있을 때만 결과 줄을 남긴다.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface SkipEntryLog {
    /** 로그를 남기지 않는 이유. 로그 문구처럼 영어로, 코드를 읽는 사람이 예외인 까닭을 알 수 있게 적는다. */
    String value();
}
