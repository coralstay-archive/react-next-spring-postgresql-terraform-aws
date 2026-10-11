package com.example.board.post;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface PostRepository extends JpaRepository<Post, Long>, JpaSpecificationExecutor<Post> {

	/** 목록 페이지의 글들에 작성자·카테고리·태그를 한 번에 붙인다(N+1 방지). 같은 영속성 컨텍스트에서 부른다. */
	@Query("select distinct p from Post p join fetch p.author left join fetch p.category left join fetch p.tags where p.id in :ids")
	List<Post> fetchDetails(Collection<Long> ids);

	@Query("select p from Post p join fetch p.author left join fetch p.category left join fetch p.tags where p.id = :id")
	Optional<Post> findDetailById(Long id);

}
