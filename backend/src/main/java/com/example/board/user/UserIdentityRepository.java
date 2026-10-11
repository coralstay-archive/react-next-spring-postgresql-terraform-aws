package com.example.board.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

	Optional<UserIdentity> findByProviderAndSubject(AuthProvider provider, String subject);

	List<UserIdentity> findByUserId(Long userId);

	/** 내 로그인 수단 목록 (연결 순서). */
	List<UserIdentity> findByUserIdOrderByLinkedAtAscIdAsc(Long userId);

	long countByUserId(Long userId);

	/** 계정에 이 수단 말고 다른 수단도 있는가 (TASK-7 B4: PHONE 로그인 제한 판정). */
	boolean existsByUserIdAndProviderNot(Long userId, AuthProvider provider);

	/** 로그인용: identity 와 계정을 한 번에 읽는다. */
	@Query("select i from UserIdentity i join fetch i.user where i.provider = :provider and i.subject = :subject")
	Optional<UserIdentity> findWithUserByProviderAndSubject(AuthProvider provider, String subject);

	/** 계정에 연결된 로그인 수단 목록. */
	@Query("select i.provider from UserIdentity i where i.user.id = :userId")
	List<AuthProvider> findProvidersByUserId(Long userId);

}
