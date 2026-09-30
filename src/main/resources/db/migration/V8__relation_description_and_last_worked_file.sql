-- 관계마다 설명을 적을 수 있다(요구사항 §10 "문서 간 관계와 관계 설명"). 관계 행 자체의 성질이므로
-- 별 표를 만들지 않고 같은 행에 둔다 — 관계가 지워지면 설명도 함께 사라져야 한다.
ALTER TABLE document_relations ADD COLUMN description TEXT NOT NULL DEFAULT '';

-- "마지막으로 작업한 파일"을 프로젝트에 직접 적는다.
--
-- 지금까지는 document.updated_at 이 가장 늦은 문서를 골랐다. 그런데 그 칸은 이름 변경·이동·잠금·
-- 복원·생성에도 움직인다. 그래서 한 시간 쓴 원고 대신 방금 이름만 바꾼 문서가 "마지막 작업"으로
-- 올라왔다. 사용자가 말하는 작업은 **본문을 저장한 것**이므로, 저장할 때만 이 칸을 옮긴다.
ALTER TABLE projects ADD COLUMN last_file_id UUID;

-- 문서를 영구 삭제하면 가리킬 것이 없어진다. 프로젝트를 지우지 않고 칸만 비운다.
ALTER TABLE projects ADD CONSTRAINT fk_projects_last_file
    FOREIGN KEY (last_file_id) REFERENCES document (id) ON DELETE SET NULL;

-- 이미 있는 프로젝트는 예전 규칙으로 한 번 채운다. 이후로는 저장이 갱신한다.
UPDATE projects p
SET last_file_id = (
    SELECT d.id
    FROM document d
    WHERE d.project_id = p.id AND d.trashed_at IS NULL
    ORDER BY d.updated_at DESC, d.id DESC
    LIMIT 1
)
WHERE p.last_file_id IS NULL;
