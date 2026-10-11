-- 게시판 검색 (TASK-10#1): 제목·본문 전문 검색 컬럼.
-- 한국어 형태소 분석기가 없으므로 'simple' 구성(공백·구두점 분리 + 소문자, 어간 처리·불용어 없음)을 쓴다.
-- AC 의 "갱신 트리거" 대신 STORED 생성 컬럼을 쓴다: INSERT·UPDATE 때 PostgreSQL 이 직접 다시 계산하므로 트리거 함수가 필요 없다.
-- 제목은 가중치 A, 본문은 B(ts_rank 를 쓸 때 제목 일치가 더 높게 나온다).
ALTER TABLE posts ADD COLUMN search_tsv tsvector GENERATED ALWAYS AS (
	setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
	setweight(to_tsvector('simple', coalesce(content, '')), 'B')
) STORED;

CREATE INDEX posts_search_tsv_ix ON posts USING gin (search_tsv);
