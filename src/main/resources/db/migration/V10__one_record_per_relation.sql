-- 관계에는 방향이 없다. 그런데 지금까지는 A→B 와 B→A 를 **각각 한 행**으로 두고, 저장할 때마다
-- 반대쪽 행을 맞춰 주고 있었다(DocumentService.mirrorRelations). 같은 사실을 두 곳에 적는 구조라,
-- 두 행이 어긋나면 한쪽 문서에서만 보이는 관계가 생긴다. 실제로 미러링이 들어오기 전(2026-09-26)에
-- 만들어진 관계는 전부 한쪽 행만 있다.
--
-- 그래서 한 쌍에 한 행만 둔다. 어느 쪽이 "출발"인지 적지 않고 두 id 를 크기 순으로 넣는다.

-- 관계 키는 **가리키는 쪽의 분류**가 정한다. 한 행에서 양쪽의 키를 모두 만들어야 하므로, 그 표가
-- 질의 안에 있어야 한다. 지금까지 Java 에만 있던 표를 분류 표 자체로 옮긴다.
ALTER TABLE base_folders ADD COLUMN relation_key VARCHAR(100);

UPDATE base_folders SET relation_key = CASE code
    WHEN 'MANUSCRIPT'   THEN 'related_manuscript'
    WHEN 'CHARACTER'    THEN 'related_character'
    WHEN 'LOCATION'     THEN 'related_place'
    WHEN 'ORGANIZATION' THEN 'related_organization'
    WHEN 'ITEM'         THEN 'related_item'
    WHEN 'EVENT'        THEN 'related_event'
    WHEN 'WORLDVIEW'    THEN 'related_worldview'
END;

ALTER TABLE base_folders ALTER COLUMN relation_key SET NOT NULL;
ALTER TABLE base_folders ADD CONSTRAINT uq_base_folders_relation_key UNIQUE (relation_key);

-- 자기 자신을 가리키는 행은 쌍이 아니다. 지금 API 는 막지만 예전 행이 있을 수 있다.
DELETE FROM document_relations WHERE document_id = target_document_id;

ALTER TABLE document_relations
    ADD COLUMN low_document_id  UUID,
    ADD COLUMN high_document_id UUID;

UPDATE document_relations
SET low_document_id  = least(document_id, target_document_id),
    high_document_id = greatest(document_id, target_document_id);

-- 같은 쌍이 두 행(양방향)으로, 때로는 여러 키로 흩어져 있다. 한 행으로 합치면서 설명이 적힌 쪽을
-- 남긴다 — 설명은 대상 문서의 것이 아니라 **연결**의 것이므로 쌍마다 하나다.
DELETE FROM document_relations r
WHERE r.id <> (
    SELECT keep.id
    FROM document_relations keep
    WHERE keep.low_document_id = r.low_document_id
      AND keep.high_document_id = r.high_document_id
    ORDER BY (keep.description <> '') DESC, keep.id
    LIMIT 1);

-- relation_key 와 position 은 더 이상 행의 성질이 아니다. 키는 반대쪽 문서의 분류에서 나오고,
-- 순서는 읽을 때 정한다. 두 칸을 남겨 두면 다시 어긋날 자리가 된다.
--
-- document_id·target_document_id 를 지우면 그 칸에 걸려 있던 외래 키·유니크·인덱스도 함께 사라진다.
ALTER TABLE document_relations
    DROP COLUMN document_id,
    DROP COLUMN target_document_id,
    DROP COLUMN relation_key,
    DROP COLUMN position;

ALTER TABLE document_relations
    ALTER COLUMN low_document_id  SET NOT NULL,
    ALTER COLUMN high_document_id SET NOT NULL,
    ADD CONSTRAINT uq_document_relations UNIQUE (low_document_id, high_document_id),
    -- 같은 쌍을 두 번 적을 수 없게 하는 것은 유니크가 아니라 이 순서 규칙이다. 순서가 없으면
    -- (A,B) 와 (B,A) 가 서로 다른 행으로 들어간다.
    ADD CONSTRAINT ck_document_relations_pair CHECK (low_document_id < high_document_id),
    ADD CONSTRAINT fk_document_relations_low FOREIGN KEY (low_document_id)
        REFERENCES document (id) ON DELETE CASCADE,
    ADD CONSTRAINT fk_document_relations_high FOREIGN KEY (high_document_id)
        REFERENCES document (id) ON DELETE CASCADE;

-- 유니크 인덱스가 low 로 시작하는 조회를 받아 준다. high 쪽만 따로 둔다.
CREATE INDEX ix_document_relations_high ON document_relations (high_document_id);
