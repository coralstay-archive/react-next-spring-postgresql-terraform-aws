package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;

/** TASK-10 검색 스키마: search_tsv(생성 컬럼) + GIN(#1), pg_trgm 트라이그램 인덱스(#2). */
@IntegrationTest
class PostSearchSchemaTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	TransactionTemplate tx;

	private String indexDef(String name) {
		return jdbc.queryForObject("select indexdef from pg_indexes where tablename = 'posts' and indexname = ?",
				String.class, name);
	}

	@Test
	@DisplayName("TASK-10#1 posts.search_tsv 는 title(A)·content(B) 의 simple tsvector 를 담는 STORED 생성 컬럼이다")
	void searchTsvIsGeneratedColumn() {
		var column = jdbc.queryForMap(
				"select data_type, is_generated, generation_expression from information_schema.columns "
						+ "where table_name = 'posts' and column_name = 'search_tsv'");

		assertThat(column.get("data_type")).isEqualTo("tsvector");
		assertThat(column.get("is_generated")).isEqualTo("ALWAYS");
		assertThat((String) column.get("generation_expression")).contains("'simple'").contains("title")
			.contains("content");
	}

	@Test
	@DisplayName("TASK-10#1 search_tsv 에 GIN 인덱스(posts_search_tsv_ix)가 있다")
	void searchTsvGinIndex() {
		assertThat(indexDef("posts_search_tsv_ix")).contains("USING gin").contains("search_tsv");
	}

	@Test
	@DisplayName("TASK-10#1 갱신 트리거 대신 생성 컬럼: INSERT·UPDATE 때 search_tsv 가 함께 바뀐다(제목 가중치 A)")
	void searchTsvFollowsWrites() {
		long authorId = fixtures.user().getId();
		long id = board.post(authorId, null, "Spring Boot", "postgres search", Instant.now());

		assertThat(tsv(id)).contains("'spring':1A").contains("'boot':2A").contains("'postgres':3").contains("'search':4");

		jdbc.update("update posts set title = 'Terraform', content = 'aws' where id = ?", id);
		assertThat(tsv(id)).contains("'terraform':1A").contains("'aws':2").doesNotContain("spring");
	}

	private String tsv(long id) {
		return jdbc.queryForObject("select search_tsv::text from posts where id = ?", String.class, id);
	}

	@Test
	@DisplayName("TASK-10#2 pg_trgm 확장과 title·content 의 gin_trgm_ops 인덱스가 있다")
	void trigramIndexes() {
		assertThat(jdbc.queryForList("select extname from pg_extension", String.class)).contains("pg_trgm");
		assertThat(indexDef("posts_title_trgm_ix")).contains("USING gin").contains("title gin_trgm_ops");
		assertThat(indexDef("posts_content_trgm_ix")).contains("USING gin").contains("content gin_trgm_ops");
	}

	@Test
	@DisplayName("TASK-10#2 한국어 부분 일치: simple tsvector 로는 못 찾는 '스프링' → '스프링부트' 를 ILIKE 가 찾는다")
	void koreanPartialMatchNeedsIlike() {
		long authorId = fixtures.user().getId();
		long id = board.post(authorId, null, "스프링부트 입문", "본문", Instant.now());

		Boolean fts = jdbc.queryForObject(
				"select search_tsv @@ websearch_to_tsquery('simple', '스프링') from posts where id = ?", Boolean.class, id);
		Boolean ilike = jdbc.queryForObject("select title ilike '%스프링%' from posts where id = ?", Boolean.class, id);

		assertThat(fts).isFalse();
		assertThat(ilike).isTrue();
	}

	@Test
	@DisplayName("TASK-10#2 ILIKE '%q%' 와 search_tsv @@ 조건에 인덱스를 쓸 수 있다(EXPLAIN 스모크, seqscan 끔)")
	void indexesAreUsable() {
		List<String> plan = tx.execute(status -> {
			jdbc.execute("set local enable_seqscan = off");
			return jdbc.queryForList("explain select id from posts where search_tsv @@ websearch_to_tsquery('simple', 'spring') "
					+ "or title ilike '%스프링%' or content ilike '%스프링%'", String.class);
		});

		String text = String.join("\n", plan);
		assertThat(text).contains("posts_search_tsv_ix").contains("posts_title_trgm_ix").contains("posts_content_trgm_ix");
	}

}
