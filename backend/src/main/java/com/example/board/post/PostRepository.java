package com.example.board.post;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PostRepository extends JpaRepository<Post, Long>, JpaSpecificationExecutor<Post> {

	/** 목록 페이지의 글들에 작성자·카테고리·태그를 한 번에 붙인다(N+1 방지). 같은 영속성 컨텍스트에서 부른다. */
	@Query("select distinct p from Post p join fetch p.author left join fetch p.category left join fetch p.tags where p.id in :ids")
	List<Post> fetchDetails(Collection<Long> ids);

	@Query("select p from Post p join fetch p.author left join fetch p.category left join fetch p.tags where p.id = :id")
	Optional<Post> findDetailById(Long id);

	/**
	 * 글 행을 공유 잠금({@code FOR SHARE})으로 읽는다. 댓글 작성(TASK-9 B3)처럼 "삭제되지 않은 글" 확인과 쓰기 사이에 글이 삭제되지
	 * 않게 한다(삭제 UPDATE 는 이 트랜잭션이 끝날 때까지 기다리고, 먼저 커밋된 삭제는 여기서 보인다).
	 */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select p from Post p where p.id = :id")
	Optional<Post> findForShareById(Long id);

}
