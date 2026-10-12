package com.example.board.admin;

import static com.example.board.support.AuthClient.quote;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.board.support.Api;
import com.example.board.support.AuthClient;
import com.example.board.support.AuthFixtures;
import com.example.board.support.BoardFixtures;
import com.example.board.support.IntegrationTest;

/**
 * TASK-20#5 (BE-41) 카테고리 저장의 DB 무결성 위반은 slug 유니크 제약({@code categories_slug_uk})일 때만 409 {@code slug_taken}이고,
 * 나머지는 공통 매핑(TASK-20#2: 22xxx → 400 validation, 그 밖 → 409 conflict)을 따른다.
 *
 * <p>사전 검사(existsBySlug)를 지난 뒤의 DB 오류를 재현하려고 테스트 동안만 트리거를 건다. 이름이 특정 값일 때만 동작한다:
 * {@code sim-race} 는 같은 slug 행을 먼저 넣어 동시 생성 경쟁을 흉내 내고(23505), {@code sim-check} 는 다른 제약 위반(23514),
 * {@code sim-data} 는 데이터 예외(22023)를 낸다.
 */
@IntegrationTest
class AdminCategoryIntegrityTest {

	@LocalServerPort
	int port;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	BoardFixtures board;

	@Autowired
	JdbcTemplate jdbc;

	Api api;

	String adminBearer;

	@BeforeEach
	void setUp() {
		api = new Api(port);
		adminBearer = fixtures.bearer(fixtures.admin());
		jdbc.execute("""
				CREATE OR REPLACE FUNCTION task20_category_sim() RETURNS trigger AS $$
				BEGIN
					IF NEW.name = 'sim-race' THEN
						INSERT INTO categories (slug, name) VALUES (NEW.slug, 'sim-other');
					ELSIF NEW.name = 'sim-check' THEN
						RAISE EXCEPTION 'new row for relation "categories" violates check constraint "categories_sim_ck"'
							USING ERRCODE = '23514', CONSTRAINT = 'categories_sim_ck';
					ELSIF NEW.name = 'sim-data' THEN
						RAISE EXCEPTION 'invalid value' USING ERRCODE = '22023';
					END IF;
					RETURN NEW;
				END;
				$$ LANGUAGE plpgsql""");
		jdbc.execute("DROP TRIGGER IF EXISTS task20_category_sim ON categories");
		jdbc.execute("""
				CREATE TRIGGER task20_category_sim BEFORE INSERT OR UPDATE ON categories
				FOR EACH ROW EXECUTE FUNCTION task20_category_sim()""");
	}

	@AfterEach
	void tearDown() {
		jdbc.execute("DROP TRIGGER IF EXISTS task20_category_sim ON categories");
		jdbc.execute("DROP FUNCTION IF EXISTS task20_category_sim()");
	}

	private HttpResponse<String> create(String name) {
		return api.post("/admin/categories")
			.bearer(adminBearer)
			.json("{\"slug\":" + quote(BoardFixtures.unique("s-")) + ",\"name\":" + quote(name) + "}")
			.send();
	}

	private HttpResponse<String> rename(String name) {
		long id = jdbc.queryForObject("select id from categories where slug = ?", Long.class, board.category());
		return api.patch("/admin/categories/" + id).bearer(adminBearer).json("{\"name\":" + quote(name) + "}").send();
	}

	private static String error(HttpResponse<String> response) {
		return AuthClient.json(response).get("error").asString();
	}

	@Test
	@DisplayName("TASK-20#5 slug 유니크 위반(동시 생성 경쟁)은 그대로 409 slug_taken (생성·수정)")
	void slugUniqueViolation() {
		HttpResponse<String> created = create("sim-race");
		assertThat(created.statusCode()).as(created.body()).isEqualTo(409);
		assertThat(error(created)).isEqualTo("slug_taken");

		HttpResponse<String> renamed = rename("sim-race");
		assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(409);
		assertThat(error(renamed)).isEqualTo("slug_taken");
	}

	@Test
	@DisplayName("TASK-20#5 다른 제약 위반은 slug_taken 이 아니라 409 conflict (생성·수정)")
	void otherConstraintViolation() {
		HttpResponse<String> created = create("sim-check");
		assertThat(created.statusCode()).as(created.body()).isEqualTo(409);
		assertThat(error(created)).isEqualTo("conflict");

		HttpResponse<String> renamed = rename("sim-check");
		assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(409);
		assertThat(error(renamed)).isEqualTo("conflict");
	}

	@Test
	@DisplayName("TASK-20#5 데이터 예외(22xxx)는 slug_taken 이 아니라 400 validation")
	void dataException() {
		HttpResponse<String> created = create("sim-data");
		assertThat(created.statusCode()).as(created.body()).isEqualTo(400);
		assertThat(error(created)).isEqualTo("validation");
	}

}
