package com.example.board.post;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import org.springframework.data.jpa.domain.Specification;

import com.example.board.tag.Tag;

/** {@link PostQuery} → JPA Specification. 조건마다 메서드 하나라 새 필터(검색 등)는 여기에 더한다. */
public final class PostSpecifications {

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
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

}
