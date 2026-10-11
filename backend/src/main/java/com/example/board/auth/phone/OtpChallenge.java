package com.example.board.auth.phone;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/** 휴대폰 OTP 챌린지 한 건 (otp_challenges). id 는 앱이 만든 UUID 라 {@link Persistable} 로 새 행임을 알린다. */
@Entity
@Table(name = "otp_challenges")
public class OtpChallenge implements Persistable<UUID> {

	public static final String PURPOSE_LOGIN = "LOGIN";

	@Id
	private UUID id;

	@Column(nullable = false, length = 32)
	private String phone;

	@Column(name = "code_hash", nullable = false, length = 128)
	private String codeHash;

	@Column(nullable = false, length = 20)
	private String purpose;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "requester_ip", length = 64)
	private String requesterIp;

	@Transient
	private boolean isNew;

	protected OtpChallenge() {
	}

	private OtpChallenge(UUID id, String phone, String codeHash, Instant createdAt, Instant expiresAt,
			String requesterIp) {
		this.id = Objects.requireNonNull(id, "id");
		this.phone = Objects.requireNonNull(phone, "phone");
		this.codeHash = Objects.requireNonNull(codeHash, "codeHash");
		this.purpose = PURPOSE_LOGIN;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
		this.requesterIp = requesterIp;
		this.isNew = true;
	}

	public static OtpChallenge issue(UUID id, String phone, String codeHash, Instant now, Instant expiresAt,
			String requesterIp) {
		return new OtpChallenge(id, phone, codeHash, now, expiresAt, requesterIp);
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	/** 아직 쓸 수 있는가: 소멸(B1)·만료(B1)·시도 초과(B2)가 아니어야 한다. */
	public boolean isUsable(Instant now, int maxAttempts) {
		return consumedAt == null && now.isBefore(expiresAt) && attempts < maxAttempts;
	}

	public void failedAttempt() {
		this.attempts++;
	}

	public void consume(Instant now) {
		this.consumedAt = now;
	}

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	public String getPhone() {
		return phone;
	}

	public String getCodeHash() {
		return codeHash;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getConsumedAt() {
		return consumedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
