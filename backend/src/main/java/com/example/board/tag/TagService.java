package com.example.board.tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.common.ApiException;

/**
 * 글에 붙일 태그를 정규화·중복 제거하고 행을 찾거나 만든다 (TASK-8 B3).
 *
 * <p>경쟁 안전 upsert: {@code INSERT ... ON CONFLICT (name) DO NOTHING} 다음에 이름으로 다시 읽는다. 같은 새 태그를 동시에 넣는
 * 두 트랜잭션은 UNIQUE 인덱스에서 줄을 서고, 뒤의 것은 앞의 것이 커밋되면 아무것도 하지 않은 채 그 행을 읽는다(READ COMMITTED 는
 * 문장마다 새 스냅샷). 여러 태그를 넣는 트랜잭션끼리 교착하지 않도록 항상 이름 순서로 넣는다.
 */
@Service
public class TagService {

	/** 글당 최대 태그 수(중복 제거 후). */
	public static final int MAX_TAGS_PER_POST = 5;

	public static final String TOO_MANY = "too_many_tags";

	private final JdbcTemplate jdbc;

	private final TagRepository tags;

	public TagService(JdbcTemplate jdbc, TagRepository tags) {
		this.jdbc = jdbc;
		this.tags = tags;
	}

	/** 원래 입력 → 정규화된 이름 집합(이름 순). 비었거나 너무 긴 태그는 400 invalid_tag, 5개 초과는 400 too_many_tags. */
	public static List<String> normalizeAll(Collection<String> raw) {
		TreeSet<String> names = new TreeSet<>();
		for (String name : raw) {
			names.add(TagNames.normalize(name));
		}
		if (names.size() > MAX_TAGS_PER_POST) {
			throw new ApiException(HttpStatus.BAD_REQUEST, TOO_MANY, "태그는 글당 " + MAX_TAGS_PER_POST + "개까지입니다");
		}
		return new ArrayList<>(names);
	}

	/** 정규화·검증 후 태그 엔티티들(없던 것은 만든다). 호출자의 트랜잭션 안에서 돈다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<Tag> resolve(Collection<String> raw) {
		List<String> names = normalizeAll(raw);
		if (names.isEmpty()) {
			return List.of();
		}
		for (String name : names) {
			jdbc.update("insert into tags (name) values (?) on conflict (name) do nothing", name);
		}
		return tags.findByNameIn(names);
	}

}
