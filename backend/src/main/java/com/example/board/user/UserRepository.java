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

	/** 요청마다 principal 을 최신 역할·상태로 바꿀 때 읽는 값 (TASK-24#2, BE-16). */
	record RoleAndStatus(Role role, UserStatus status) {
	}

	/** 인증된 요청마다 최신 역할·상태만 가볍게 읽는다 (TASK-24#2). 계정이 없으면 empty. */
	@Query("select new com.example.board.user.UserRepository$RoleAndStatus(u.role, u.status) from User u where u.id = :id")
	Optional<RoleAndStatus> findRoleAndStatusById(Long id);

	/**
	 * 사용자 행을 FOR SHARE 로 잠그고 status 를 읽는다 (TASK-24#3: 로그인의 refresh 저장과 정지 트랜잭션의 FOR UPDATE 가 줄을 서게).
	 * 계정이 없으면 empty.
	 */
	@Query(value = "select status from users where id = :id for share", nativeQuery = true)
	Optional<String> lockStatusForShare(Long id);

	/** 이 역할의 계정이 하나라도 있는지 (TASK-21#3: 첫 ADMIN 부트스트랩은 ADMIN 이 없을 때만). */
	boolean existsByRole(Role role);

	/** 계정 단위 직렬화용 행 잠금 (예: identity 해제의 "마지막 하나" 판정). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(Long id);

}
