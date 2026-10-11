package com.example.board.auth.token;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * 로테이션용: 해당 행을 {@code SELECT ... FOR UPDATE} 로 잠근다. 같은 토큰으로 동시에 refresh 하면 뒤 요청은 앞 요청의
	 * 커밋을 기다렸다가 "이미 로테이션된 토큰"을 보게 된다 (B3 재사용 감지).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
	Optional<RefreshToken> findForUpdateByTokenHash(String tokenHash);

	/** family 의 아직 살아 있는 토큰을 모두 폐기한다. 바뀐 행 수를 돌려준다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
	int revokeFamily(UUID familyId, Instant now);

	/** 사용자의 살아 있는 refresh 토큰을 family 구분 없이 모두 폐기한다 (TASK-12 관리자 정지). 바뀐 행 수를 돌려준다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
	int revokeAllOfUser(Long userId, Instant now);

}
