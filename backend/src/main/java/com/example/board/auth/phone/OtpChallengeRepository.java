package com.example.board.auth.phone;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

	/**
	 * 검증용: 챌린지 행을 {@code SELECT ... FOR UPDATE} 로 잠근다. 같은 챌린지의 동시 검증은 여기서 줄을 서므로, 시도 횟수
	 * 증가·소멸 판정이 앞 요청의 커밋 결과를 보고 이뤄진다 (B1·B2).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from OtpChallenge c where c.id = :id")
	Optional<OtpChallenge> findForUpdateById(UUID id);

	/** 소모를 되돌린다 (TASK-25#5 BE-22). 소모된 챌린지만 바꾸고, 바꾼 행 수를 돌려준다. */
	@Modifying
	@Query("update OtpChallenge c set c.consumedAt = null where c.id = :id and c.consumedAt is not null")
	int release(UUID id);

}
