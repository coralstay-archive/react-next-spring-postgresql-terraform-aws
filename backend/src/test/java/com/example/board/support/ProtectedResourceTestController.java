package com.example.board.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.board.auth.AuthUser;

/**
 * 테스트 전용 보호 리소스. 소유자가 있는 리소스를 메모리에 두고 보안 매트릭스를 검증한다.
 * 운영 코드에는 없다(src/test).
 */
@RestController("testResources")
public class ProtectedResourceTestController {

	private final AtomicLong sequence = new AtomicLong();

	private final Map<Long, Long> owners = new ConcurrentHashMap<>();

	/** 테스트가 남의 리소스를 직접 만들 때 쓴다. */
	public long createOwnedBy(long ownerId) {
		long id = sequence.incrementAndGet();
		owners.put(id, ownerId);
		return id;
	}

	public Long ownerOf(Long id) {
		Long owner = owners.get(id);
		if (owner == null) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		return owner;
	}

	@GetMapping("/test/whoami")
	public Map<String, Object> whoami(@AuthenticationPrincipal AuthUser user) {
		return Map.of("id", user.id(), "role", user.role().name(), "status", user.status().name());
	}

	@GetMapping("/test/admin-only")
	@PreAuthorize("hasRole('ADMIN')")
	public Map<String, Object> adminOnly() {
		return Map.of("ok", true);
	}

	/** 세션 쿠키 속성 검증용: 세션을 새로 만들게 한다. */
	@GetMapping("/test/session/touch")
	public Map<String, Object> touchSession(HttpServletRequest request) {
		request.getSession(true).setAttribute("touched", true);
		return Map.of("ok", true);
	}

	@GetMapping("/test/resources/{id}")
	public Map<String, Object> read(@PathVariable Long id) {
		return Map.of("id", id, "ownerId", ownerOf(id));
	}

	@PostMapping("/test/resources")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(@AuthenticationPrincipal AuthUser user) {
		long id = createOwnedBy(user.id());
		return Map.of("id", id, "ownerId", user.id());
	}

	@PatchMapping("/test/resources/{id}")
	@PreAuthorize("@ownership.canModify(authentication, @testResources.ownerOf(#id))")
	public Map<String, Object> patch(@PathVariable Long id) {
		return Map.of("id", id, "updated", true);
	}

	@PutMapping("/test/resources/{id}")
	@PreAuthorize("@ownership.canModify(authentication, @testResources.ownerOf(#id))")
	public Map<String, Object> put(@PathVariable Long id) {
		return Map.of("id", id, "updated", true);
	}

	@DeleteMapping("/test/resources/{id}")
	@PreAuthorize("@ownership.canModify(authentication, @testResources.ownerOf(#id))")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		owners.remove(id);
	}

}
