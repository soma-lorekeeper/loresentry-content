-- 문서의 대표 이미지. 문서 제목 옆과 관계도 노드에 보인다.
--
-- 본문·속성이 아니라 문서 행의 칸이다. 버전 스냅샷에 들지 않으므로 본문을 옛 버전으로 복원해도 그림은 그대로이고,
-- 최신화 제안과 AI 챗이 읽는 문서 내용에도 섞이지 않는다. revision_no 도 올리지 않는다 — 이미지를 바꿨다고 열려
-- 있는 편집기의 다음 저장이 충돌하면 안 된다.
ALTER TABLE document ADD COLUMN image_id UUID;

-- 다른 프로젝트의 이미지를 가리킬 수 없다. 복합 외래 키가 대상 쪽에 (id, project_id) 유일성을 요구한다.
ALTER TABLE image ADD CONSTRAINT uq_image_id_project UNIQUE (id, project_id);

-- 이미지 행이 지워지면 문서의 칸만 비운다. project_id 는 문서의 것이라 그대로 둬야 한다.
ALTER TABLE document ADD CONSTRAINT fk_document_image FOREIGN KEY (image_id, project_id)
    REFERENCES image (id, project_id) ON DELETE SET NULL (image_id);

-- 이미지 하나는 문서 하나의 것이다. 바꾸거나 지울 때 이전 이미지를 같이 지우므로, 둘이 나눠 가지면 한쪽이 깨진다.
CREATE UNIQUE INDEX uq_document_image ON document (image_id) WHERE image_id IS NOT NULL;
