package com.example.board.admin;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 관리자 감사 로그 한 행 (TASK-12, V10). 만든 뒤에는 바꾸지 않는다. */
@Entity
@Table(name = "admin_actions")
public class AdminAction {

	/** 관리 액션 종류. DB 에는 이름 그대로 저장한다. */
	public enum Type {

		USER_SUSPEND(Target.USER), USER_UNSUSPEND(Target.USER), USER_SESSIONS_REVOKE(Target.USER), POST_HIDE(Target.POST),
		POST_UNHIDE(Target.POST),
		POST_RESTORE(Target.POST), CATEGORY_CREATE(Target.CATEGORY), CATEGORY_UPDATE(Target.CATEGORY),
		CATEGORY_DELETE(Target.CATEGORY);

		final Target target;

		Type(Target target) {
			this.target = target;
		}

	}

	public enum Target {
		USER, POST, CATEGORY
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "admin_id", nullable = false, updatable = false)
	private Long adminId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40, updatable = false)
	private Type action;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false, length = 20, updatable = false)
	private Target targetType;

	@Column(name = "target_id", nullable = false, updatable = false)
	private Long targetId;

	@Column(length = 500, updatable = false)
	private String reason;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AdminAction() {
	}

	static AdminAction of(Long adminId, Type action, Long targetId, String reason, Instant now) {
		AdminAction row = new AdminAction();
		row.adminId = adminId;
		row.action = action;
		row.targetType = action.target;
		row.targetId = targetId;
		row.reason = reason;
		row.createdAt = now;
		return row;
	}

	public Long getId() {
		return id;
	}

	public Long getAdminId() {
		return adminId;
	}

	public Type getAction() {
		return action;
	}

	public Target getTargetType() {
		return targetType;
	}

	public Long getTargetId() {
		return targetId;
	}

	public String getReason() {
		return reason;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
