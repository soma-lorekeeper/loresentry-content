-- V15 가 더한 것 중 쓰지 않는 테이블과 컬럼을 되돌린다. refresh_results 는 최신화 결과 원본을 보관하는 데
-- 다시 쓰므로 남긴다(아래 "refresh_results" 참고).
DROP TABLE projection_heads;
ALTER TABLE refresh_runs
    DROP COLUMN input_fingerprint,
    DROP COLUMN result_bucket,
    DROP COLUMN result_key;

-- 추출에 쓴 규칙 버전은 graph-rag 가 정하고 완료 이벤트(GraphRefreshCompleted.prompt_version)로 알려 준다.
-- 요청을 기록하는 시점에는 아직 모른다.
ALTER TABLE refresh_runs ALTER COLUMN prompt_version DROP NOT NULL;

-- 최신화 요청마다 S3 입력에 실은 문서와 그때의 revision. 고치지 않고 쌓기만 한다.
--
-- 다음 요청의 기준선은 같은 프로젝트에서 FAILED·CANCELLED 가 아닌 run 에 기록된 문서별 max(revision_no)다.
-- revision 은 오르기만 하므로 max 가 곧 마지막으로 보낸 값이다. run 이 실패하면 그 run 의 행이 집계에서
-- 빠지므로, 그 문서들은 되돌리는 처리 없이 다음 요청에 다시 실린다.
CREATE TABLE refresh_run_files (
    refresh_run_id UUID   NOT NULL,
    -- 문서 외래 키를 두지 않는다. 문서가 지워져도 그 run 에 무엇을 보냈는지는 남는다.
    file_id        UUID   NOT NULL,
    revision_no    BIGINT NOT NULL,
    CONSTRAINT pk_refresh_run_files PRIMARY KEY (refresh_run_id, file_id),
    -- 프로젝트를 지우면 refresh_runs 와 함께 사라진다.
    CONSTRAINT fk_refresh_run_files_run FOREIGN KEY (refresh_run_id)
        REFERENCES refresh_runs (id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------------------------------------
-- refresh_runs: 완료 처리에 필요한 상태와 실패 코드
-- ---------------------------------------------------------------------------------------------------------

-- 실패 사유를 기계가 읽는 코드로 남긴다(graph-rag 의 error.code, TIMED_OUT, RESULT_EXPIRED 등).
-- 사람이 읽는 설명은 기존 error_message 에 둔다.
ALTER TABLE refresh_runs ADD COLUMN error_code VARCHAR(64);

-- V15 시절 코드가 남긴, 지금 흐름에 없는 상태의 run 을 닫는다. 이 run 들은 작업본이 없어 검토·확정할 수 없고,
-- 아래에서 READY 도 새 요청을 막게 바꾸므로 남겨 두면 그 프로젝트는 최신화를 다시 할 수 없다.
UPDATE refresh_runs
SET status       = 'FAILED',
    error_code   = 'SUPERSEDED',
    completed_at = coalesce(completed_at, now())
WHERE status IN ('CAPTURING_BASE', 'READY', 'APPLYING');

-- NO_CHANGE: graph-rag 가 성공했지만 제안이 하나도 없다. 검토할 것이 없으므로 READY 와 구분한다.
ALTER TABLE refresh_runs DROP CONSTRAINT ck_refresh_runs_status;
ALTER TABLE refresh_runs ADD CONSTRAINT ck_refresh_runs_status CHECK (status IN (
    'CAPTURING_BASE', 'GENERATING', 'READY', 'NO_CHANGE', 'APPLYING', 'APPLIED', 'FAILED', 'CANCELLED'));

-- 검토를 기다리는 결과(READY)가 있는 동안에도 새 요청을 막는다. 확정하기 전에 다음 결과가 오면 같은 문서에
-- 작업본이 둘 생긴다. 이름은 그대로 둔다(요청 코드가 이 제약 위반을 "진행 중"으로 읽는다).
DROP INDEX uq_refresh_runs_one_in_progress;
CREATE UNIQUE INDEX uq_refresh_runs_one_in_progress ON refresh_runs (project_id)
    WHERE status IN ('CAPTURING_BASE', 'GENERATING', 'READY');

-- 시간 제한 작업이 오래된 GENERATING run 만 빠르게 찾는다.
CREATE INDEX ix_refresh_runs_generating_started ON refresh_runs (started_at) WHERE status = 'GENERATING';

-- ---------------------------------------------------------------------------------------------------------
-- refresh_results: graph-rag 결과(result.json) 원본
-- ---------------------------------------------------------------------------------------------------------

-- payload 에는 result.json 전체(제안·사실·근거·출처·execution)를 그대로 둔다. S3 의 원본은 며칠 뒤 만료되므로
-- 나중에 "그때 무엇을 왜 제안했는지" 볼 수 있는 곳은 여기뿐이다. 화면과 확정은 작업본을 쓰고 이 행은 읽지 않는다.
-- jsonb 는 저장할 때 바이트를 정규화하므로 원본 바이트의 sha256 과 읽어 온 위치를 따로 남긴다.
ALTER TABLE refresh_results
    ADD COLUMN result_bucket TEXT,
    ADD COLUMN result_key    TEXT,
    ADD COLUMN sha256        VARCHAR(64);

-- ---------------------------------------------------------------------------------------------------------
-- document: 최신화 잠금
-- ---------------------------------------------------------------------------------------------------------

-- 최신화에 들어간 문서를 잠근 run. NULL 이면 최신화 잠금이 없다. 사용자가 거는 잠금(locked)과 따로 둔다 —
-- run 이 끝날 때 이 run 의 잠금만 풀어야 하고, 사용자가 원래 잠가 둔 문서는 계속 잠겨 있어야 한다.
-- 편집 가능 여부는 둘 중 하나라도 걸려 있으면 잠김이다. run 이 지워지면(프로젝트 삭제) 잠금도 사라진다.
ALTER TABLE document ADD COLUMN refresh_lock_run_id UUID;
ALTER TABLE document ADD CONSTRAINT fk_document_refresh_lock_run FOREIGN KEY (refresh_lock_run_id)
    REFERENCES refresh_runs (id) ON DELETE SET NULL;
CREATE INDEX ix_document_refresh_lock_run ON document (refresh_lock_run_id) WHERE refresh_lock_run_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------
-- refresh_document_drafts: 검토 작업본
-- ---------------------------------------------------------------------------------------------------------

-- EDIT: 기존 문서를 고치는 작업본. NEW: graph-rag 가 제안한 새 설정 문서. 새 문서는 아직 없으므로 대상 문서도,
-- 기준 버전도 없다. graph-rag 가 목록 API 로 더 읽은 문서는 요청 때 기준 버전을 뜨지 않으므로 EDIT 도
-- 기준 버전이 없을 수 있다.
ALTER TABLE refresh_document_drafts ALTER COLUMN target_document_id DROP NOT NULL;
ALTER TABLE refresh_document_drafts ALTER COLUMN base_version_id DROP NOT NULL;

ALTER TABLE refresh_document_drafts
    -- EDIT | NEW
    ADD COLUMN kind            VARCHAR(10)  NOT NULL DEFAULT 'EDIT',
    -- NEW 작업본의 graph-rag 임시 이름(new:{분류}:{이름}). 확정 때 실제 문서 id 로 바뀐다.
    ADD COLUMN candidate_id    VARCHAR(300),
    -- NEW 작업본을 만들 분류 폴더
    ADD COLUMN folder_code     VARCHAR(40),
    -- 처음 뜬 left. left 는 검토 중에 고쳐지므로, 확정 때 "무엇이 바뀌었는지"(특히 관계)를 이 값과 비교한다.
    ADD COLUMN origin_snapshot JSONB,
    -- graph-rag 가 본 문서 revision 이 left 의 revision 과 다르다(목록 API 로 읽은 뒤 문서가 바뀌었다).
    -- 화면이 "AI 가 본 뒤 문서가 바뀌었음"을 알릴 때 쓴다.
    ADD COLUMN base_mismatch   BOOLEAN      NOT NULL DEFAULT false;

-- 두 작업본(left, right)이 같은가. 사용자가 양쪽을 맞추면 true 가 되고, 확정은 모든 작업본이 true 일 때만 된다.
-- 클라이언트가 보내는 값이 아니라 DB 가 계산한다. 아직 결과가 오지 않은 작업본(BASE_CAPTURED)은 false 다.
-- STORED 로 둔다 — PG18 의 기본(VIRTUAL)이면 큰 jsonb 를 읽을 때마다 비교한다.
ALTER TABLE refresh_document_drafts ADD COLUMN matched BOOLEAN GENERATED ALWAYS AS
    (status <> 'BASE_CAPTURED' AND left_snapshot IS NOT DISTINCT FROM right_snapshot) STORED;

ALTER TABLE refresh_document_drafts
    ADD CONSTRAINT fk_refresh_document_drafts_folder FOREIGN KEY (folder_code) REFERENCES base_folders (code),
    -- 한 run 에서 같은 새 문서 제안은 하나다. (run, target) 유일 제약은 NEW 의 NULL 대상끼리는 걸리지 않는다.
    ADD CONSTRAINT uq_refresh_document_drafts_run_candidate UNIQUE (refresh_run_id, candidate_id),
    ADD CONSTRAINT ck_refresh_document_drafts_kind CHECK (
        (kind = 'EDIT' AND target_document_id IS NOT NULL AND candidate_id IS NULL AND folder_code IS NULL)
        OR (kind = 'NEW' AND target_document_id IS NULL AND base_version_id IS NULL
            AND candidate_id IS NOT NULL AND folder_code IS NOT NULL AND folder_code <> 'MANUSCRIPT'
            AND left_source_revision_no IS NULL AND origin_snapshot IS NULL));

-- EDIT 은 언제나 left(와 그 원본, revision)를 갖는다. 요청 때 left 만 채워 만들고(BASE_CAPTURED), 결과가 오면
-- right 를 채운다(OPEN). NEW 는 left 가 비어 있다가 사용자가 받아들이면 right 와 같아진다.
ALTER TABLE refresh_document_drafts DROP CONSTRAINT ck_refresh_document_drafts_snapshots;
ALTER TABLE refresh_document_drafts ADD CONSTRAINT ck_refresh_document_drafts_snapshots CHECK (
    kind = 'NEW'
    OR (left_source_revision_no IS NOT NULL
        AND left_snapshot IS NOT NULL
        AND origin_snapshot IS NOT NULL
        AND (status NOT IN ('OPEN', 'APPLIED', 'STALE') OR right_snapshot IS NOT NULL)));
