package com.example.board.post;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

import com.example.board.tag.Tag;

/** {@link PostQuery} → JPA Specification. 조건마다 메서드 하나라 새 필터(검색 등)는 여기에 더한다. */
public final class PostSpecifications {

	static final char LIKE_ESCAPE = '\\';

	private PostSpecifications() {
	}

	public static Specification<Post> of(PostQuery query) {
		return (root, criteria, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (!query.includeHidden()) {
				// B2: 보이는 글만. 부분 인덱스 조건과 같다.
				predicates.add(cb.isNull(root.get("deletedAt")));
				predicates.add(cb.isNull(root.get("hiddenAt")));
			}
			if (query.categorySlug() != null) {
				predicates.add(cb.equal(root.join("category").get("slug"), query.categorySlug()));
			}
			if (query.tag() != null) {
				Subquery<Long> tagged = criteria.subquery(Long.class);
				Root<Post> sameRow = tagged.correlate(root);
				Join<Post, Tag> tags = sameRow.join("tags");
				tagged.select(tags.get("id")).where(cb.equal(tags.get("name"), query.tag()));
				predicates.add(cb.exists(tagged));
			}
			if (query.q() != null) {
				// TASK-10#3: p.id in (select d.id from posts d where d.search_tsv @@ websearch_to_tsquery('simple', q)
				// or d.title ilike '%q%' or d.content ilike '%q%'). 비상관 서브쿼리라 PostgreSQL 이 세 인덱스의 BitmapOr 로 푼다.
				Subquery<Long> matched = criteria.subquery(Long.class);
				Root<PostSearchDocument> doc = matched.from(PostSearchDocument.class);
				matched.select(doc.get("id")).where(matches(doc, query.q(), (HibernateCriteriaBuilder) cb));
				predicates.add(root.get("id").in(matched));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	/** 전문 검색(영문 등 단어 단위) OR 제목·본문 부분 일치(한국어 등, pg_trgm 인덱스). */
	private static Predicate matches(Root<PostSearchDocument> doc, String q, HibernateCriteriaBuilder cb) {
		// cb.literal 은 SQL 에 그대로 박히므로 cb.value(바인드 파라미터)를 쓴다.
		String pattern = "%" + escapeLike(q) + "%";
		return cb.or(
				cb.isTrue(cb.function(SearchFunctions.FTS_MATCH, Boolean.class, doc.get("searchTsv"), cb.value(q))),
				cb.ilike(doc.get("title"), pattern, LIKE_ESCAPE), cb.ilike(doc.get("content"), pattern, LIKE_ESCAPE));
	}

	/** ILIKE 패턴에서 {@code \}·{@code %}·{@code _} 를 글자 그대로 찾도록 이스케이프한다. */
	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
