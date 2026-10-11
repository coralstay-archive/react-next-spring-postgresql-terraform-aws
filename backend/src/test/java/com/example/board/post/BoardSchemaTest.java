package com.example.board.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.board.category.Category;
import com.example.board.category.CategoryRepository;
import com.example.board.support.AuthFixtures;
import com.example.board.support.IntegrationTest;
import com.example.board.tag.Tag;
import com.example.board.tag.TagRepository;
import com.example.board.user.User;

/** TASK-8#1 게시판 스키마(Flyway V5: categories, posts, tags, post_tags)와 JPA 매핑. */
@IntegrationTest
class BoardSchemaTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	AuthFixtures fixtures;

	@Autowired
	PostRepository posts;

	@Autowired
	CategoryRepository categories;

	@Autowired
	TagRepository tags;

	@Autowired
	TransactionTemplate tx;

	@Test
	@DisplayName("TASK-8#1 categories·posts·tags·post_tags 테이블이 Flyway 로 만들어진다")
	void tablesExist() {
		List<String> tables = jdbc.queryForList(
				"select table_name from information_schema.tables where table_schema = 'public'", String.class);

		assertThat(tables).contains("categories", "posts", "tags", "post_tags");
	}

	@Test
	@DisplayName("TASK-8#1 기본 카테고리 notice(공지, ADMIN 전용)·free(자유게시판)가 sort_order 순으로 시드된다")
	void defaultCategoriesSeeded() {
		Category notice = categories.findBySlug("notice").orElseThrow();
		Category free = categories.findBySlug("free").orElseThrow();

		assertThat(notice.getName()).isEqualTo("공지");
		assertThat(notice.isAdminOnly()).isTrue();
		assertThat(free.getName()).isEqualTo("자유게시판");
		assertThat(free.isAdminOnly()).isFalse();
		assertThat(notice.getSortOrder()).isLessThan(free.getSortOrder());
		assertThat(notice.getCreatedAt()).isNotNull();
	}

	@Test
	@DisplayName("TASK-8#1 categories.slug·tags.name 은 UNIQUE, post_tags 는 (post_id, tag_id) PK")
	void uniqueConstraints() {
		assertThatThrownBy(() -> jdbc.update("insert into categories (slug, name) values ('free', '중복')"))
			.isInstanceOf(DataIntegrityViolationException.class);

		String name = "u" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("insert into tags (name) values (?)", name);
		assertThatThrownBy(() -> jdbc.update("insert into tags (name) values (?)", name))
			.isInstanceOf(DataIntegrityViolationException.class);

		User author = fixtures.user();
		Long postId = jdbc.queryForObject(
				"insert into posts (author_id, title, content) values (?, 't', 'c') returning id", Long.class,
				author.getId());
		Long tagId = jdbc.queryForObject("select id from tags where name = ?", Long.class, name);
		jdbc.update("insert into post_tags (post_id, tag_id) values (?, ?)", postId, tagId);
		assertThatThrownBy(() -> jdbc.update("insert into post_tags (post_id, tag_id) values (?, ?)", postId, tagId))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("TASK-8#1 posts 는 soft delete(deleted_at)·숨김(hidden_at) 컬럼과 목록용 부분 인덱스를 가진다")
	void postColumnsAndIndexes() {
		List<String> columns = jdbc.queryForList(
				"select column_name from information_schema.columns where table_name = 'posts'", String.class);
		assertThat(columns).contains("id", "author_id", "category_id", "title", "content", "created_at",
				"updated_at", "deleted_at", "hidden_at");

		List<String> indexes = jdbc.queryForList("select indexname from pg_indexes where tablename in ('posts', 'post_tags')",
				String.class);
		assertThat(indexes).contains("posts_visible_latest_ix", "posts_category_visible_latest_ix",
				"posts_author_id_ix", "post_tags_tag_id_ix");
		String latest = jdbc.queryForObject("select indexdef from pg_indexes where indexname = 'posts_visible_latest_ix'",
				String.class);
		assertThat(latest).contains("created_at DESC", "id DESC", "deleted_at IS NULL", "hidden_at IS NULL");
	}

	@Test
	@DisplayName("TASK-8#1 Post 를 작성자·카테고리·태그와 함께 JPA 로 저장하고 다시 읽는다")
	void jpaRoundTrip() {
		User author = fixtures.user();
		String tagName = "j" + UUID.randomUUID().toString().substring(0, 8);
		Long id = tx.execute(status -> {
			Tag tag = tags.save(Tag.named(tagName));
			Post post = Post.write(author, categories.findBySlug("free").orElseThrow(), "제목", "본문");
			post.replaceTags(List.of(tag));
			return posts.saveAndFlush(post).getId();
		});

		tx.executeWithoutResult(status -> {
			Post found = posts.findById(id).orElseThrow();
			assertThat(found.getOwnerId()).isEqualTo(author.getId());
			assertThat(found.getCategory().getSlug()).isEqualTo("free");
			assertThat(found.getTags()).extracting(Tag::getName).containsExactly(tagName);
			assertThat(found.getCreatedAt()).isNotNull();
			assertThat(found.getUpdatedAt()).isNotNull();
			assertThat(found.isDeleted()).isFalse();
			assertThat(found.isHidden()).isFalse();
		});
	}

}
