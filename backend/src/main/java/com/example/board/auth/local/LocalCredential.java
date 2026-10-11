package com.example.board.auth.local;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * LOCAL identity 의 비밀번호 (local_credentials). 해시는 {@code DelegatingPasswordEncoder} 형식({@code {bcrypt}...})만 저장한다
 * (TASK-3 B4).
 */
@Entity
@Table(name = "local_credentials")
public class LocalCredential {

	@Id
	@Column(name = "identity_id")
	private Long identityId;

	@Column(name = "password_hash", nullable = false, length = 255)
	private String passwordHash;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected LocalCredential() {
	}

	private LocalCredential(Long identityId, String passwordHash) {
		this.identityId = Objects.requireNonNull(identityId, "identityId");
		this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
	}

	public static LocalCredential of(Long identityId, String passwordHash) {
		return new LocalCredential(identityId, passwordHash);
	}

	@PrePersist
	@PreUpdate
	void touch() {
		this.updatedAt = Instant.now();
	}

	public Long getIdentityId() {
		return identityId;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

}
