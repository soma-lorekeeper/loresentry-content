-- 같은 위치에 같은 이름을 허용하지 않는다(요구사항 §3.3). 프로젝트 이름과 같은 이유로
-- 애플리케이션 선검사가 아니라 인덱스로 막는다 — 선검사는 동시 요청 두 개를 통과시킨다.
-- 휴지통 문서는 이름을 풀어 준다.
CREATE UNIQUE INDEX uq_document_active_title
    ON document (project_id, folder_id,
                 coalesce(episode_id, '00000000-0000-0000-0000-000000000000'::uuid),
                 lower(title))
    WHERE trashed_at IS NULL;

CREATE UNIQUE INDEX uq_episode_folders_name
    ON episode_folders (project_id, lower(name));

-- 목록과 검색이 항상 활성 문서만 본다. 부분 인덱스라야 휴지통 행이 끼지 않는다.
CREATE INDEX ix_document_active_project
    ON document (project_id, folder_id, rank)
    WHERE trashed_at IS NULL;

CREATE INDEX ix_document_trashed_project
    ON document (project_id, trashed_at DESC)
    WHERE trashed_at IS NOT NULL;
