package com.example.board.user;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.example.board.common.ClockEntityListener;
import com.example.board.common.Timestamped;

/** 통합 계정 (decision-4). email/phone 은 표시·연락용이며 로그인 키가 아니다. */
@Entity
@EntityListeners(ClockEntityListener.class)
@Table(name = "users")
public class User implements Timestamped {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String nickname;

	@Column(length = 320)
	private String email;

	@Column(length = 32)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role = Role.USER;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserStatus status = UserStatus.ACTIVE;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected User() {
	}

	private User(String nickname) {
		this.nickname = Objects.requireNonNull(nickname, "nickname");
	}

	public static User create(String nickname) {
		return new User(nickname);
	}

	@Override
	public void onCreate(Instant now) {
		this.createdAt = now;
		this.updatedAt = now;
	}

	@Override
	public void onUpdate(Instant now) {
		this.updatedAt = now;
	}

	public void changeNickname(String nickname) {
		this.nickname = Objects.requireNonNull(nickname, "nickname");
	}

	public void changeEmail(String email) {
		this.email = email;
	}

	public void changePhone(String phone) {
		this.phone = phone;
	}

	public void changeRole(Role role) {
		this.role = Objects.requireNonNull(role, "role");
	}

	public void suspend() {
		this.status = UserStatus.SUSPENDED;
	}

	public void activate() {
		this.status = UserStatus.ACTIVE;
	}

	public boolean isAdmin() {
		return role == Role.ADMIN;
	}

	public Long getId() {
		return id;
	}

	public String getNickname() {
		return nickname;
	}

	public String getEmail() {
		return email;
	}

	public String getPhone() {
		return phone;
	}

	public Role getRole() {
		return role;
	}

	public UserStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
