package com.loresentry.content.outbox;

/**
 * relay 의 한 회차의 결과. {@link OutboxPublisher#publishBatch()} 가 만들어 돌려주고 {@link OutboxRelay} 가 읽는다.
 * 부르는 쪽은 {@code outcome} 으로 다음 행동을 정하고, 개수는 요약 로그에 쓴다.
 *
 * @param outcome 부르는 쪽이 다음에 할 일
 * @param published 발행 표시를 한 행의 수
 * @param quarantined 격리한 행의 수
 * @param failed 횟수와 이유를 남기고 미발행으로 둔 행의 수. 다음 회차에 다시 나간다
 */
record Round(Outcome outcome, int published, int quarantined, int failed) {

    /**
     * 회차가 끝난 뒤 부르는 쪽이 어떻게 해야 하는지. {@link OutboxRelay} 가 이 값을 보고 이어서 돌지, 쉴지, 간격을
     * 늘릴지를 정한다.
     *
     * <p>회차에서 일어난 일의 종류가 아니라, 부르는 쪽이 다르게 반응해야 하는 경우의 수만큼만 나눈다.
     */
    enum Outcome {

        /** 보냈고 다시 보낼 실패가 없다. 행이 더 남아 있을 수 있으므로 곧바로 다음 회차를 돌려도 된다. */
        MORE,

        /** 할 일이 없었다. 보낼 행이 없거나 다른 인스턴스가 돌리고 있다. 평소 주기만큼 쉬면 된다. */
        IDLE,

        /** 다시 보내야 할 실패가 있었거나 종료 신호로 중단됐다. broker 쪽 문제일 수 있으므로 간격을 늘려 쉰다. */
        FAILED
    }
}
