package com.example.board.admin;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감사 로그 기록 (TASK-12). 호출한 관리 서비스의 트랜잭션 안에서만 쓴다({@code MANDATORY}): 관리 액션이 롤백되면 기록도 같이
 * 롤백되고, 기록이 실패하면 관리 액션도 커밋되지 않는다.
 */
@Component
public class AdminAudit {

	private final AdminActionRepository actions;

	private final Clock clock;

	public AdminAudit(AdminActionRepository actions, Clock clock) {
		this.actions = actions;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void record(Long adminId, AdminAction.Type action, Long targetId, String reason) {
		actions.save(AdminAction.of(adminId, action, targetId, blankToNull(reason), clock.instant()));
	}

	static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
