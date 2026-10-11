-- 게시판 검색 (TASK-10#2): 한국어 부분 일치 보조.
-- 'simple' tsvector 는 공백 단위 토큰이라 '스프링' 으로 '스프링부트' 를 찾지 못한다. 그래서 title·content 에 ILIKE '%q%' 를 함께 쓰고,
-- pg_trgm 의 GIN 트라이그램 인덱스로 그 ILIKE 를 인덱스로 풀 수 있게 한다(3자 이상 패턴에서 효과가 크다).
-- postgres 공식 이미지(로컬·테스트)와 RDS 모두 pg_trgm 을 기본 제공한다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX posts_title_trgm_ix ON posts USING gin (title gin_trgm_ops);
CREATE INDEX posts_content_trgm_ix ON posts USING gin (content gin_trgm_ops);
