-- 본문을 에디터 문서 구조(JSON)로 저장한다. Markdown 문자열은 글자와 서식 기호를 한 곳에 섞어,
-- 사용자가 쓴 일반 문단(`# 해시로 시작`, `1. 번호처럼`, `++더하기++`)을 다시 열 때 제목·목록·밑줄로
-- 바꿔 버렸다. 문단별 정렬·들여쓰기를 담을 자리도 없다.
ALTER TABLE document ADD COLUMN body_json JSONB;

-- 서버가 body_json 에서 뽑은 순수 텍스트. 검색·글자 수·앞으로의 AI 추출이 쓴다.
-- 클라이언트가 보내지 않는다.
ALTER TABLE document ADD COLUMN body_text TEXT NOT NULL DEFAULT '';

UPDATE document SET body_text = body_md;

-- body_md 는 지우지 않는다. body_json 이 NULL 인 레거시 행을 읽는 데 쓰고 새로 쓰지 않는다.
-- 모든 행이 변환된 뒤 후속 마이그레이션에서 지운다.
