package com.example.board.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

	/** 인가 시점에 최신 상태만 가볍게 읽는다 (세션·토큰에 실린 상태는 낡았을 수 있다). */
	@Query("select u.status from User u where u.id = :id")
	Optional<UserStatus> findStatusById(Long id);

}
