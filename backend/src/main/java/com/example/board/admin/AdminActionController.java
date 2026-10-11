package com.example.board.admin;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.common.PageResponse;
import com.example.board.user.User;
import com.example.board.user.UserRepository;

/** 감사 로그 조회 (TASK-12, 관리자 화면 TASK-15 용). 최근순. 관리자 닉네임은 페이지의 admin id 로 한 번에 읽는다. */
@RestController
public class AdminActionController {

	public record AdminActionResponse(Long id, Long adminId, String adminNickname, String action, String targetType,
			Long targetId, String reason, Instant createdAt) {
	}

	private final AdminActionRepository actions;

	private final UserRepository users;

	public AdminActionController(AdminActionRepository actions, UserRepository users) {
		this.actions = actions;
		this.users = users;
	}

	@GetMapping("/admin/actions")
	@Transactional(readOnly = true)
	public PageResponse<AdminActionResponse> list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		AdminUserController.checkPage(page, size);
		Page<AdminAction> result = actions.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, size));
		Map<Long, String> nicknames = users
			.findAllById(result.stream().map(AdminAction::getAdminId).collect(Collectors.toSet()))
			.stream()
			.collect(Collectors.toMap(User::getId, User::getNickname, (a, b) -> a));
		Function<AdminAction, AdminActionResponse> mapper = row -> new AdminActionResponse(row.getId(),
				row.getAdminId(), nicknames.get(row.getAdminId()), row.getAction().name(), row.getTargetType().name(),
				row.getTargetId(), row.getReason(), row.getCreatedAt());
		return PageResponse.of(result, mapper);
	}

}
