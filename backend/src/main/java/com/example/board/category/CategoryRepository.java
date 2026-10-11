package com.example.board.category;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, Long> {

	Optional<Category> findBySlug(String slug);

	List<Category> findAllByOrderBySortOrderAscIdAsc();

	boolean existsBySlug(String slug);

	/** 새 카테고리의 기본 정렬 값(맨 뒤)용. 카테고리가 없으면 null. */
	@Query("select max(c.sortOrder) from Category c")
	Integer findMaxSortOrder();

}
