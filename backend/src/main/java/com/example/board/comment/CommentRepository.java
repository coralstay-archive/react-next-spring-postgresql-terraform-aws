package com.example.board.comment;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CommentRepository extends JpaRepository<Comment, Long> {

	/** 글의 댓글 페이지. 작성자를 같이 읽는다(N+1 방지). 정렬은 Pageable 이 준다. */
	@EntityGraph(attributePaths = "author")
	@Query("select c from Comment c where c.post.id = :postId")
	Page<Comment> findPageByPostId(Long postId, Pageable pageable);

	/** 삭제 인가·처리용: 댓글 + 글(가시성 판정). */
	@Query("select c from Comment c join fetch c.post where c.id = :id")
	Optional<Comment> findWithPostById(Long id);

}
