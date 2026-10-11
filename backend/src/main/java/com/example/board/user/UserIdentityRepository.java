package com.example.board.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

	Optional<UserIdentity> findByProviderAndSubject(AuthProvider provider, String subject);

	List<UserIdentity> findByUserId(Long userId);

}
