package com.example.board.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.criteria.Predicate;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.board.auth.AuthUser;
import com.example.board.auth.token.RefreshTokenRepository;
import com.example.board.common.ApiException;
import com.example.board.common.PageResponse;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;
import com.example.board.user.UserStatus;

/**
 * 관리자 사용자 관리 (TASK-12#1).
 *
 * <p>정지는 즉시 효과가 있어야 한다. 한 트랜잭션에서 (1) status=SUSPENDED, (2) 그 사용자의 refresh 토큰 전부(모든 family) 폐기,
 * (3) 감사 로그를 커밋하고, 커밋 뒤에 (4) Spring Session JDBC 에서 그 사용자의 세션을 모두 지운다. 세션은 principal name 인덱스
 * ({@code SPRING_SESSION.PRINCIPAL_NAME})로 찾는다. 이 값은 Spring Session 이 세션의 SecurityContext 에서
 * {@code Authentication#getName()} 으로 뽑는데, {@link com.example.board.auth.AuthUserAuthentication} 의 이름이 사용자 id 라
 * 세션 로그인·소셜 로그인·OTP 로그인 모두 {@code String.valueOf(userId)} 로 색인된다.
 *
 * <p>세션 삭제를 커밋 뒤에 하는 이유: 롤백될 수 있는 정지 때문에 세션을 먼저 지우지 않기 위해서다. 커밋과 삭제 사이의 짧은 틈에도
 * 쓰기는 {@code SuspendedUserWriteFilter}(DB 최신 status)가 막고, 로그인은 status 를 보고 403 이다. 이미 발급된 access JWT 는
 * 만료(15분)까지 읽기만 된다(쓰기는 같은 필터가 403).
 *
 * <p>B2: 자기 자신은 정지할 수 없다(409 {@code cannot_suspend_self}). 다른 ADMIN 도 정지할 수 없다(409
 * {@code cannot_suspend_admin}) — 관리자끼리 서로 잠그는 것을 막고, 역할 변경 API 가 없으므로(범위 밖) ADMIN 정지는 DB 작업으로만 한다.
 */
@Service
public class AdminUserService {

	public static final String CANNOT_SUSPEND_SELF = "cannot_suspend_self";

	public static final String CANNOT_SUSPEND_ADMIN = "cannot_suspend_admin";

	private static final char LIKE_ESCAPE = '\\';

	private static final Log log = LogFactory.getLog(AdminUserService.class);

	private final UserRepository users;

	private final UserIdentityRepository identities;

	private final RefreshTokenRepository refreshTokens;

	private final FindByIndexNameSessionRepository<?> sessions;

	private final AdminAudit audit;

	private final TransactionTemplate tx;

	private final TransactionTemplate readOnlyTx;

	private final Clock clock;

	public AdminUserService(UserRepository users, UserIdentityRepository identities,
			RefreshTokenRepository refreshTokens, FindByIndexNameSessionRepository<?> sessions, AdminAudit audit,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.users = users;
		this.identities = identities;
		this.refreshTokens = refreshTokens;
		this.sessions = sessions;
		this.audit = audit;
		this.tx = new TransactionTemplate(transactionManager);
		this.readOnlyTx = new TransactionTemplate(transactionManager);
		this.readOnlyTx.setReadOnly(true);
		this.clock = clock;
	}

	/** 관리자 목록의 사용자 한 줄. providers 는 연결 순서. */
	public record AdminUserResponse(Long id, String nickname, String email, String phone, String role, String status,
			List<AuthProvider> providers, Instant createdAt) {

		static AdminUserResponse of(User user, List<AuthProvider> providers) {
			return new AdminUserResponse(user.getId(), user.getNickname(), user.getEmail(), user.getPhone(),
					user.getRole().name(), user.getStatus().name(), providers, user.getCreatedAt());
		}

	}

	/**
	 * 최근 가입순 목록. {@code q} 는 닉네임·이메일 부분 일치(대소문자 무시) 또는 전화번호 부분 일치. 전화번호는 E.164 로 저장되므로
	 * 숫자만 남긴 값과, 국내 표기(앞자리 0)면 0 을 뗀 값으로도 찾는다({@code 010-1234-5678} → {@code +821012345678}). 로그인 수단은
	 * 페이지의 사용자 id 로 한 번에 읽는다(N+1 없음).
	 */
	public PageResponse<AdminUserResponse> list(String q, UserStatus status, int page, int size) {
		return readOnlyTx.execute(ignored -> {
			Page<User> result = users.findAll(search(q, status),
					PageRequest.of(page, size, Sort.by(Sort.Order.desc("id"))));
			Map<Long, List<AuthProvider>> providers = new LinkedHashMap<>();
			result.forEach(user -> providers.put(user.getId(), new ArrayList<>()));
			if (!providers.isEmpty()) {
				for (Object[] row : identities.findProvidersByUserIdIn(providers.keySet())) {
					providers.get((Long) row[0]).add((AuthProvider) row[1]);
				}
			}
			return PageResponse.of(result, user -> AdminUserResponse.of(user, providers.get(user.getId())));
		});
	}

	static Specification<User> search(String q, UserStatus status) {
		return (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			if (status != null) {
				where.add(cb.equal(root.get("status"), status));
			}
			String term = q == null ? "" : q.strip();
			if (!term.isEmpty()) {
				String pattern = "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
				List<Predicate> any = new ArrayList<>();
				any.add(cb.like(cb.lower(root.get("nickname")), pattern, LIKE_ESCAPE));
				any.add(cb.like(cb.lower(root.get("email")), pattern, LIKE_ESCAPE));
				any.add(cb.like(root.get("phone"), "%" + escapeLike(term) + "%", LIKE_ESCAPE));
				for (String digits : phoneDigits(term)) {
					any.add(cb.like(root.get("phone"), "%" + digits + "%"));
				}
				where.add(cb.or(any.toArray(Predicate[]::new)));
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
	}

	/** 검색어의 숫자만 남긴 값(2자리 이상)과, 0 으로 시작하면(국내 표기) 0 을 뗀 값. */
	static Set<String> phoneDigits(String term) {
		String digits = term.replaceAll("\\D", "");
		Set<String> result = new java.util.LinkedHashSet<>();
		if (digits.length() >= 2) {
			result.add(digits);
		}
		if (digits.length() >= 3 && digits.startsWith("0")) {
			result.add(digits.substring(1));
		}
		return result;
	}

	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	/** 정지(멱등). 커밋 뒤 세션 삭제까지 마치고 돌려준다. */
	public AdminUserResponse suspend(AuthUser admin, Long userId, String reason) {
		if (admin.id().equals(userId)) {
			throw new ApiException(HttpStatus.CONFLICT, CANNOT_SUSPEND_SELF, "자기 자신은 정지할 수 없습니다");
		}
		AdminUserResponse response = tx.execute(ignored -> {
			User user = users.findByIdForUpdate(userId).orElseThrow(AdminUserService::notFound);
			if (user.isAdmin()) {
				throw new ApiException(HttpStatus.CONFLICT, CANNOT_SUSPEND_ADMIN, "관리자 계정은 정지할 수 없습니다");
			}
			user.suspend();
			int revoked = refreshTokens.revokeAllOfUser(userId, clock.instant());
			audit.record(admin.id(), AdminAction.Type.USER_SUSPEND, userId, reason);
			log.info("관리자 정지: admin=" + admin.id() + " user=" + userId + " refresh 폐기=" + revoked);
			return toResponse(users.saveAndFlush(user));
		});
		int deleted = deleteSessions(userId);
		log.info("정지 사용자 세션 삭제: user=" + userId + " 세션=" + deleted);
		return response;
	}

	/** 정지 해제(멱등). 끊긴 세션·토큰은 되살리지 않는다(다시 로그인한다). */
	public AdminUserResponse unsuspend(AuthUser admin, Long userId, String reason) {
		return tx.execute(ignored -> {
			User user = users.findByIdForUpdate(userId).orElseThrow(AdminUserService::notFound);
			user.activate();
			audit.record(admin.id(), AdminAction.Type.USER_UNSUSPEND, userId, reason);
			return toResponse(users.saveAndFlush(user));
		});
	}

	/** principal name(사용자 id) 인덱스로 그 사용자의 세션을 모두 지운다. 지운 개수를 돌려준다. */
	int deleteSessions(Long userId) {
		Set<String> ids = sessions.findByPrincipalName(String.valueOf(userId)).keySet();
		ids.forEach(sessions::deleteById);
		return ids.size();
	}

	private AdminUserResponse toResponse(User user) {
		return AdminUserResponse.of(user, identities.findProvidersByUserId(user.getId()));
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "사용자를 찾을 수 없습니다");
	}

}
