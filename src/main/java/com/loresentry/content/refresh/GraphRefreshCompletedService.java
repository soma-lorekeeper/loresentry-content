package com.loresentry.content.refresh;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * GraphRefreshCompleted 이벤트를 받았을 때 할 일. graph-rag 서비스가 최신화를 마치면(성공이든 실패든) 보낸다.
 *
 * <p>Kafka 에서 받는 쪽(inbox package 의 listener)이 처리 기록과 같은 트랜잭션 안에서 부른다. 그래서
 * {@code MANDATORY} 다 — 이미 열린 트랜잭션에 합류하고, 트랜잭션 없이 불리면 실행하기 전에 예외를 던진다. 처리 기록
 * 없이 처리만 되는 길을 막는다.
 *
 * <p>여기서 하는 DB 쓰기는 같은 thread 에서, 새 트랜잭션을 열지 않고 해야 한다. 다른 thread 로 넘기거나
 * {@code REQUIRES_NEW} 로 새로 열면 그 쓰기는 처리 기록과 따로 확정된다. 결과 파일(S3)을 읽는 것처럼 느린 일은 이
 * 트랜잭션 안에서 하지 않는 편이 좋다 — 그동안 DB connection 을 잡고 있게 된다.
 */
@Service
public class GraphRefreshCompletedService {

    /**
     * 최신화 완료 이벤트 하나를 처리한다.
     *
     * @param event 끝난 최신화의 요청 id, 결과 또는 실패 원인
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(GraphRefreshCompletedEvent event) {
        // 다음 task: request_id 의 refresh_runs 행을 outcome 에 따라 READY 나 FAILED 로 바꾼다. 같은 프로젝트의 결과는
        // 같은 partition 으로 와서 차례로 처리되지만, 상태를 바꿀 때는 지금 상태를 조건으로 건다(예: GENERATING 일 때만).
        // 그때까지 listener 는 kafka.consumer.enabled.graph-refresh-completed 로 꺼 둔다. 켜면 받은 이벤트를 처리한 것으로
        // 기록만 한다.
    }
}
