-- TASK-23#3 (BE-27·BE-39) 글 낙관적 잠금. 엔티티 저장(작성자 수정·삭제, 관리자 숨김·해제·복구)마다 +1 하고, 읽은 뒤 다른 저장이 먼저
-- 커밋됐으면 UPDATE ... WHERE version = ? 가 0행이라 409 conflict. like_count·view_count 의 SQL ±1 은 버전을 건드리지 않는다.
ALTER TABLE posts ADD COLUMN version bigint NOT NULL DEFAULT 0;
