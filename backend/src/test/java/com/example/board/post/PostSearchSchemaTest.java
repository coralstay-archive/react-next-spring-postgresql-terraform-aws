package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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

}
