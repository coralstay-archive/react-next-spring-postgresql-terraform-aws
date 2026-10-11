package com.example.board.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * 검색 조건 전용 읽기 매핑 (TASK-10#3). {@code posts} 테이블을 한 번 더 매핑해 {@code search_tsv} 를 Criteria 에서 참조할 수 있게 한다.
 *
 * <p>{@link Post} 에 {@code search_tsv} 를 매핑하면 글을 읽을 때마다 tsvector 를 함께 가져오므로, 검색 조건의 서브쿼리
 * ({@code p.id in (select d.id from PostSearchDocument d where ...)})에서만 쓰는 별도 엔티티로 둔다. 이 엔티티로 행을 읽거나 쓰지
 * 않는다.
 */
@Entity
@Immutable
@Table(name = "posts")
public class PostSearchDocument {

	@Id
	private Long id;

	@Column(nullable = false, length = 100, insertable = false, updatable = false)
	private String title;

	@Column(nullable = false, columnDefinition = "text", insertable = false, updatable = false)
	private String content;

	/** STORED 생성 컬럼(V7). 값은 쓰지 않고 조건에서만 쓴다. */
	@Column(name = "search_tsv", columnDefinition = "tsvector", insertable = false, updatable = false)
	private String searchTsv;

	protected PostSearchDocument() {
	}

	public Long getId() {
		return id;
	}

}
