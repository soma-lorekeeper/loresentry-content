-- 메모. 프로젝트 전체에 붙는 것과 특정 문서에 붙는 것을 한 테이블에 둔다 — 본문·수정 시각·삭제가
-- 똑같고, 나누면 목록 질의만 두 벌이 된다. 구분은 scope 이고 CHECK 가 둘을 섞이지 않게 한다.
CREATE TABLE memo (
    id          UUID         NOT NULL DEFAULT uuidv7(),
    project_id  UUID         NOT NULL,
    scope       VARCHAR(10)  NOT NULL,
    document_id UUID,
    title       VARCHAR(200),
    body        TEXT         NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_memo PRIMARY KEY (id),
    CONSTRAINT fk_memo_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE CASCADE,
    -- 문서를 영구 삭제하면 그 문서의 메모도 사라진다(요구사항 §3.6).
    CONSTRAINT fk_memo_document FOREIGN KEY (document_id)
        REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT ck_memo_scope CHECK (scope IN ('PROJECT', 'FILE')),
    -- FILE 이면 문서가 있어야 하고, PROJECT 면 없어야 한다. 한쪽만 맞으면 목록이 새거나 빠진다.
    CONSTRAINT ck_memo_document_matches_scope
        CHECK ((scope = 'FILE') = (document_id IS NOT NULL))
);

CREATE INDEX ix_memo_project_updated ON memo (project_id, updated_at DESC)
    WHERE scope = 'PROJECT';
CREATE INDEX ix_memo_document_updated ON memo (document_id, updated_at DESC)
    WHERE scope = 'FILE';

-- 즐겨찾기는 원본을 가리키는 바로가기다(요구사항 §4.2) — 복제가 아니므로 행에 제목·순서가 없다.
-- 사이드바 메뉴가 문서에만 붙으므로 대상은 문서뿐이다.
CREATE TABLE favorite (
    project_id  UUID        NOT NULL,
    document_id UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_favorite PRIMARY KEY (project_id, document_id),
    CONSTRAINT fk_favorite_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_favorite_document FOREIGN KEY (document_id)
        REFERENCES document (id) ON DELETE CASCADE
);

CREATE INDEX ix_favorite_project_created ON favorite (project_id, created_at);

-- 작업공간 복원 상태. 탭·패널·그래프 보기를 화면이 만든 JSON 그대로 보관한다.
-- 서버가 그 구조를 해석하지 않는다 — 화면 레이아웃이 바뀔 때마다 마이그레이션을 하지 않으려면
-- 여기서는 불투명한 값이어야 한다.
--
-- 사용자당 프로젝트당 한 행이다. owner_user_id 를 키에 넣는 이유는 나중에 한 프로젝트를 여러
-- 사용자가 열게 되어도 서로의 탭을 덮어쓰지 않게 하기 위해서다.
CREATE TABLE workspace_state (
    project_id    UUID        NOT NULL,
    owner_user_id UUID        NOT NULL,
    layout        JSONB       NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_workspace_state PRIMARY KEY (project_id, owner_user_id),
    CONSTRAINT fk_workspace_state_project FOREIGN KEY (project_id)
        REFERENCES projects (id) ON DELETE CASCADE
);
