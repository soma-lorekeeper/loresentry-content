-- 앱 안에서 보내는 의견. 운영자가 SQL 로 읽는다. 사용자 키는 owner_user_id 처럼 값으로만 갖는다 —
-- 사용자 테이블은 authentication 데이터베이스에 있다.
CREATE TABLE feedback (
    id         UUID         NOT NULL DEFAULT uuidv7(),
    user_id    UUID         NOT NULL,
    category   VARCHAR(16)  NOT NULL,
    message    TEXT         NOT NULL,
    page       VARCHAR(200),
    client     VARCHAR(300),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_feedback PRIMARY KEY (id),
    CONSTRAINT ck_feedback_category CHECK (category IN ('BUG', 'IDEA', 'OTHER'))
);

CREATE INDEX ix_feedback_created ON feedback (created_at DESC);
CREATE INDEX ix_feedback_user_created ON feedback (user_id, created_at DESC);
