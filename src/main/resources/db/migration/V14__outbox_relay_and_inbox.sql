-- outbox 를 relay 가 읽어 Kafka 로 보낼 수 있는 모양으로 보강하고, 받은 이벤트의 중복을 거르는
-- inbox 를 만든다. 이벤트 계약(topic, key, header)은 팀 Kafka 설계 문서의 "Message 계약"을 따른다.
--
-- V1 의 컬럼은 그대로 메시지가 된다: id → header ce_id, event_type → header ce_type,
-- created_at → header ce_time, payload → value. 여기서 더하는 것은 V1 에 없던 순서, key, 실패 처리다.
--
-- 이 테이블에 쓰는 코드가 아직 없어 행이 없다. 행이 있으면 event_key 의 NOT NULL 에서 멈춘다 —
-- key 를 모르는 이벤트를 아무 값으로 채워 넣는 것보다 멈추는 편이 낫다.
ALTER TABLE outbox_events
    -- 발행 순서. created_at 은 트랜잭션이 시작한 시각이라 커밋 순서와 다를 수 있고, uuidv7 도
    -- 세션이 다르면 순서를 보장하지 않는다. seq 는 INSERT 하는 순간 번호를 받는다.
    ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY,
    -- Kafka key. payload 에서 꺼내지 않고 따로 둔다 — relay 가 본문이 든 JSON 을 파싱하지 않아도
    -- 되고, 앞 행이 실패한 key 의 뒤 행을 건너뛰어 순서를 지키는 데도 쓴다.
    ADD COLUMN event_key VARCHAR(100) NOT NULL,
    -- W3C Trace Context(header traceparent). relay 는 요청이 끝난 뒤 다른 스레드에서 돌므로,
    -- 추적을 이으려면 행에 적어 두어야 한다. 추적을 붙이기 전까지는 비어 있다.
    -- 버전 00 형식은 항상 55자다.
    ADD COLUMN traceparent VARCHAR(55),
    -- 발행 실패 횟수와 마지막 원인. 기록일 뿐 격리 기준이 아니다 — broker 장애가 길어지면 멀쩡한
    -- 행의 횟수도 함께 오르므로, 횟수로 격리하면 장애와 문제 있는 행을 구분하지 못한다.
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_error TEXT,
    -- 다시 보내도 성공할 수 없는 행(크기 한도 초과 등)을 격리한 시각. relay 는 이 행을 더 보지 않는다.
    ADD COLUMN quarantined_at TIMESTAMPTZ;

-- relay 가 찾는 것은 아직 보내지 않았고 격리되지도 않은 행이다. 부분 인덱스라 발행된 행이 쌓여도
-- 커지지 않는다. 정렬 기준이 created_at 에서 seq 로 바뀌므로 V1 의 인덱스를 바꾼다.
DROP INDEX ix_outbox_events_unpublished;

CREATE INDEX ix_outbox_events_pending ON outbox_events (seq)
    WHERE published_at IS NULL AND quarantined_at IS NULL;

-- 받은 이벤트를 처리했다는 기록. Kafka 는 같은 메시지를 두 번 줄 수 있으므로(at-least-once),
-- 처리 트랜잭션이 여기에 먼저 INSERT 해 보고 이미 있으면 건너뛴다. payload 는 담지 않는다 —
-- 중복 판정에 필요한 것은 id 뿐이고, 종류나 offset 은 로그에 남긴다.
CREATE TABLE inbox_events (
    consumer_name   VARCHAR(100)    NOT NULL,                -- 어느 처리기가 처리했나
    event_id        UUID            NOT NULL,                             -- header ce_id
    processed_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),    -- 처리 시각, 정리 기준
    CONSTRAINT      pk_inbox_events PRIMARY KEY (consumer_name, event_id)
);
