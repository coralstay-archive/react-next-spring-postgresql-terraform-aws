package com.example.board.auth.token;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * refresh 토큰 한 개 (refresh_tokens). 원문은 저장하지 않고 SHA-256 해시만 둔다.
 *
 * <ul>
 * <li>{@code familyId}: 로그인 한 번에서 이어지는 로테이션 체인. 재사용이 감지되면 family 전체를 폐기한다 (TASK-3 B3).</li>
 * <li>{@code replacedBy}: 로테이션으로 바뀐 경우 새 토큰 id. 값이 있으면 "이미 쓴 토큰"이다.</li>
 * <li>{@code revokedAt}: 로테이션·로그아웃·family 폐기 시각. 값이 있으면 더는 쓸 수 없다.</li>
 * </ul>
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "token_hash", nullable = false, length = 128)
	private String tokenHash;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "replaced_by")
	private Long replacedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** 제한 토큰 family (TASK-7 B4). 로테이션해도 유지된다. */
	@Column(name = "step_up_required", nullable = false)
	private boolean stepUpRequired;

	protected RefreshToken() {
	}

	private RefreshToken(Long userId, String tokenHash, UUID familyId, Instant expiresAt, boolean stepUpRequired) {
		this.userId = Objects.requireNonNull(userId, "userId");
		this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
		this.familyId = Objects.requireNonNull(familyId, "familyId");
		this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
		this.stepUpRequired = stepUpRequired;
	}

	public static RefreshToken issue(Long userId, String tokenHash, UUID familyId, Instant expiresAt) {
		return issue(userId, tokenHash, familyId, expiresAt, false);
	}

	public static RefreshToken issue(Long userId, String tokenHash, UUID familyId, Instant expiresAt,
			boolean stepUpRequired) {
		return new RefreshToken(userId, tokenHash, familyId, expiresAt, stepUpRequired);
	}

	public boolean isStepUpRequired() {
		return stepUpRequired;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}

	/** 로테이션: 이 토큰은 폐기되고 {@code next} 로 대체됐다. */
	public void rotatedTo(RefreshToken next, Instant now) {
		this.replacedBy = Objects.requireNonNull(next.getId(), "next.id");
		this.revokedAt = now;
	}

	public boolean isRotated() {
		return replacedBy != null;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isExpired(Instant now) {
		return !expiresAt.isAfter(now);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public UUID getFamilyId() {
		return familyId;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public Long getReplacedBy() {
		return replacedBy;
	}

}
