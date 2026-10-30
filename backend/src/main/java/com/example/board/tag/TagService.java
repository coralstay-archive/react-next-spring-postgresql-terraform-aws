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

	/**
	 * 정규화·검증 후 태그 엔티티들(없던 것은 만든다). 호출자의 트랜잭션 안에서 돈다.
	 *
	 * <p>미사용 태그 정리와의 경쟁 (TASK-27#1): upsert 뒤 태그 행을 {@code FOR KEY SHARE} 로 잠가 커밋 때까지 쥔다 — 정리는
	 * {@code FOR UPDATE SKIP LOCKED} 로 고르므로 저장 중인 글의 태그를 건너뛴다. KEY SHARE 끼리는 충돌하지 않아 같은 태그를 쓰는 글 저장끼리는
	 * 기다리지 않는다. 정리가 먼저 잠근 태그는 그 삭제가 끝날 때까지 기다리고, 지워졌으면(잠금 결과에 없으면) 다시 upsert 한다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<Tag> resolve(Collection<String> raw) {
		List<String> names = normalizeAll(raw);
		if (names.isEmpty()) {
			return List.of();
		}
		List<String> missing = names;
		for (int attempt = 0; attempt < MAX_RESOLVE_ATTEMPTS && !missing.isEmpty(); attempt++) {
			for (String name : missing) {
				jdbc.update("insert into tags (name) values (?) on conflict (name) do nothing", name);
			}
			List<String> held = jdbc.queryForList(
					"select name from tags where name = any(?) order by name for key share", String.class,
					(Object) names.toArray(String[]::new));
			missing = names.stream().filter(name -> !held.contains(name)).toList();
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException("태그를 잡지 못했다(정리 작업과 계속 겹침): " + missing.size() + "개");
		}
		return tags.findByNameIn(names);
	}

	/** 정리 작업이 같은 태그를 거듭 지우는 경우의 재시도 상한. 정리는 한 번 실행에 한 번만 지나가므로 보통 두 번째에 끝난다. */
	static final int MAX_RESOLVE_ATTEMPTS = 3;

}
