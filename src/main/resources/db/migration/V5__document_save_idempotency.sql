-- 저장 재시도를 구분한다. 네트워크가 끊겨 응답을 못 받은 클라이언트가 같은 저장을 다시 보낼 때,
-- 같은 X-Save-Id 면 revision 을 또 올리지 않고 현재 상태를 돌려준다.
ALTER TABLE document ADD COLUMN last_save_id UUID;
