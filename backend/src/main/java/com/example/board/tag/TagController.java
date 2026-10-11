package com.example.board.tag;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.common.ApiException;

/**
 * 태그 목록 (TASK-8#4). 공개. 보이는 글(삭제·숨김 제외)에 쓰인 태그만, 글 수가 많은 순(같으면 이름 순).
 * {@code q} 는 이름 접두어(태그와 같은 방식으로 소문자·공백 정규화, % · _ 는 글자 그대로), {@code limit} 1~100(기본 20).
 *
 * <p>요청마다 post_tags 를 집계한다. 글이 많아지면 태그별 카운터 컬럼이나 캐시로 바꾼다.
 */
@RestController
public class TagController {

	static final int MAX_LIMIT = 100;

	public record TagCount(String name, long postCount) {
	}

	private final JdbcTemplate jdbc;

	public TagController(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/tags")
	public List<TagCount> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "20") int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "validation", "limit 은 1~" + MAX_LIMIT + " 이어야 합니다");
		}
		String prefix = TagNames.normalizePrefix(q);
		return jdbc.query("""
				select t.name, count(*) as post_count
				from tags t
				join post_tags pt on pt.tag_id = t.id
				join posts p on p.id = pt.post_id
				where p.deleted_at is null and p.hidden_at is null and t.name like ? escape '\\'
				group by t.name
				order by post_count desc, t.name asc
				limit ?
				""", (rs, i) -> new TagCount(rs.getString("name"), rs.getLong("post_count")),
				escapeLike(prefix) + "%", limit);
	}

	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
