-- 업로드된 사용자 이미지. 바이트는 S3 에 있고 이 테이블은 그 객체의 기록이다.
--
-- PENDING 은 티켓만 발급된 상태다. 브라우저가 S3 에 직접 올리므로 서버는 업로드가 끝났는지
-- 알 수 없고, complete 호출이 HeadObject 로 확인한 뒤에야 COMMITTED 가 된다. 끝내 올라오지
-- 않은 PENDING 행과 그 고아 객체는 정리 배치가 필요하다(IMAGE_UPLOAD_S3.md §4.2).
CREATE TABLE image (
    id           UUID         NOT NULL DEFAULT uuidv7(),
    project_id   UUID         NOT NULL,
    file_name    TEXT,
    s3_key       TEXT         NOT NULL,
    content_type TEXT         NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    committed_at TIMESTAMPTZ,
    CONSTRAINT pk_image PRIMARY KEY (id),
    CONSTRAINT uq_image_s3_key UNIQUE (s3_key),
    CONSTRAINT fk_image_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT ck_image_status CHECK (status IN ('PENDING', 'COMMITTED')),
    CONSTRAINT ck_image_size CHECK (size_bytes >= 0),
    -- COMMITTED 면 확인 시각이 있어야 한다. 없으면 "확인했다"는 주장에 근거가 없다.
    CONSTRAINT ck_image_committed_at CHECK (status <> 'COMMITTED' OR committed_at IS NOT NULL)
);

CREATE INDEX ix_image_project_created ON image (project_id, created_at DESC);

-- 정리 배치가 찾아야 하는 행. 부분 인덱스라야 COMMITTED 가 끼지 않는다.
CREATE INDEX ix_image_pending ON image (created_at) WHERE status = 'PENDING';
