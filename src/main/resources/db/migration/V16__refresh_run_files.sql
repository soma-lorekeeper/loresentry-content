-- V15 가 더한 테이블과 컬럼을 되돌린다.
DROP TABLE refresh_results;
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
