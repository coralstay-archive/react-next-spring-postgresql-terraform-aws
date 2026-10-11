package com.example.board.user;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

	/** 인가 시점에 최신 상태만 가볍게 읽는다 (세션·토큰에 실린 상태는 낡았을 수 있다). */
	@Query("select u.status from User u where u.id = :id")
	Optional<UserStatus> findStatusById(Long id);

	/** 계정 단위 직렬화용 행 잠금 (예: identity 해제의 "마지막 하나" 판정). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(Long id);

}
