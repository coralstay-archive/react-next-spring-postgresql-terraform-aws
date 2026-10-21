package com.example.board.user;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.example.board.common.ClockEntityListener;
import com.example.board.common.Timestamped;

/** 계정에 붙은 로그인 수단 하나. (provider, subject) 가 유일한 로그인 키다. */
@Entity
@EntityListeners(ClockEntityListener.class)
@Table(name = "user_identities")
public class UserIdentity implements Timestamped {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AuthProvider provider;

	@Column(nullable = false, length = 255)
	private String subject;

	@Column(length = 320)
	private String email;

	@Column(name = "linked_at", nullable = false, updatable = false)
	private Instant linkedAt;

	protected UserIdentity() {
	}

	private UserIdentity(User user, AuthProvider provider, String subject, String email) {
		this.user = Objects.requireNonNull(user, "user");
		this.provider = Objects.requireNonNull(provider, "provider");
		this.subject = Objects.requireNonNull(subject, "subject");
		this.email = email;
	}

	public static UserIdentity link(User user, AuthProvider provider, String subject, String email) {
		return new UserIdentity(user, provider, subject, email);
	}

	@Override
	public void onCreate(Instant now) {
		this.linkedAt = now;
	}

	public Long getId() {
		return id;
	}

	public User getUser() {
		return user;
	}

	public AuthProvider getProvider() {
		return provider;
	}

	public String getSubject() {
		return subject;
	}

	public String getEmail() {
		return email;
	}

	public Instant getLinkedAt() {
		return linkedAt;
	}

}
