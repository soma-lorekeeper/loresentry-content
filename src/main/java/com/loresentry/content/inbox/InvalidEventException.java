package com.loresentry.content.inbox;

/**
 * 받은 이벤트가 계약에 맞지 않는다. 같은 메시지를 다시 읽어도 결과가 같으므로 재시도해도 소용없다.
 *
 * <p>이 예외는 "우리가 검사해서 거부한 것"만 나타낸다. 필수 header 가 없거나 값이 틀린 경우, payload 를 읽을 수
 * 없는 경우, 있어야 하는 필드가 없는 경우다. DB 에 닿지 못한 것처럼 다시 하면 될 수 있는 실패에는 쓰지 않는다 —
 * 그런 실패는 일어난 예외 그대로 올려 보낸다.
 *
 * <p>종류(이 클래스)는 코드가 읽고 메시지는 사람이 읽는다. 던지는 쪽은 이 예외가 어떻게 다뤄질지 정하지 않는다.
 * 재시도할지 말지는 예외의 종류를 보고 받는 쪽이 정한다.
 */
public class InvalidEventException extends RuntimeException {

    /**
     * @param message 어느 검사에 걸렸는지. 받은 값이나 payload 의 내용은 넣지 않는다 — 로그에 적어도 되는 글자만
     *         담기 위해서다. 받은 값은 원본 메시지에 그대로 남아 있다.
     */
    public InvalidEventException(String message) {
        super(message);
    }
}
