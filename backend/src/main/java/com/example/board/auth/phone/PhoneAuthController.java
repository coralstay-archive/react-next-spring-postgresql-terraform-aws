package com.example.board.auth.phone;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.board.auth.AuthController;
import com.example.board.auth.AuthUser;
import com.example.board.auth.SessionLogin;
import com.example.board.auth.UserSummary;
import com.example.board.auth.token.TokenService;
import com.example.board.common.ApiException;
import com.example.board.security.BearerRequests;
import com.example.board.security.ClientIpResolver;
import com.example.board.security.StepUpRequiredException;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;
import com.example.board.user.UserStatus;

/** 휴대폰 OTP 로그인 API ({@code /api/auth/phone/*}, TASK-7). 공개 엔드포인트. 세션 경로는 CSRF 검사 대상이다. */
@RestController
public class PhoneAuthController {

	public static final String INVALID_OTP = "invalid_otp";

	private final PhoneOtpService otp;

	private final PhoneIdentityService phoneIdentities;

	private final ClientIpResolver clientIp;

	private final SessionLogin sessionLogin;

	private final TokenService tokens;

	private final UserRepository users;

	private final UserIdentityRepository identities;

	public PhoneAuthController(PhoneOtpService otp, PhoneIdentityService phoneIdentities, ClientIpResolver clientIp,
			SessionLogin sessionLogin, TokenService tokens, UserRepository users, UserIdentityRepository identities) {
		this.otp = otp;
		this.phoneIdentities = phoneIdentities;
		this.clientIp = clientIp;
		this.sessionLogin = sessionLogin;
		this.tokens = tokens;
		this.users = users;
		this.identities = identities;
	}

	/** 인증번호 발송. 202 {@code {challengeId, expiresIn}}. 계정이 있든 없든 같은 응답이다. */
	@PostMapping("/auth/phone/otp")
	public ResponseEntity<OtpChallengeResponse> otp(@Valid @RequestBody PhoneOtpRequest body,
			HttpServletRequest request) {
		return ResponseEntity.accepted().body(otp.send(body.phone(), clientIp.resolve(request)));
	}

	/**
	 * 인증번호 검증.
	 *
	 * <ul>
	 * <li>기본: (PHONE, E.164) 계정으로 로그인(없으면 가입)하고 웹 세션을 만든다({@link SessionLogin}). CSRF 검사 대상.</li>
	 * <li>{@code ?token=jwt}: 세션 없이 access+refresh 를 본문으로. LOCAL 의 {@code login?token=jwt} 와 같은 이유로 CSRF 면제
	 * ({@code SecurityConfig#csrfExempt}).</li>
	 * <li>{@code ?link=true}: 로그인한 계정(세션 또는 Bearer)에 PHONE identity 를 붙인다. 앰비언트 자격(세션)을 쓰므로 CSRF 면제가
	 * 아니고 token 과 같이 쓸 수 없다. 다른 계정의 번호면 409 identity_in_use.</li>
	 * </ul>
	 * 코드가 틀리거나 챌린지가 없·만료·소멸·폐기됐으면 모두 같은 401 {@code invalid_otp}. 정지 계정은 403.
	 *
	 * <p>B4 재활용 번호 보호: 로그인한 계정에 PHONE 말고 다른 수단(LOCAL·소셜·Cognito)도 있으면 제한 세션·토큰
	 * ({@code stepUpRequired})이다. 제한 세션은 연결 모드를 쓸 수 없다(403 step_up_required).
	 */
	@PostMapping("/auth/phone/verify")
	public Object verify(@Valid @RequestBody PhoneVerifyRequest body,
			@RequestParam(name = "token", required = false) String token,
			@RequestParam(name = "link", defaultValue = "false") boolean link, @AuthenticationPrincipal AuthUser principal,
			HttpServletRequest request, HttpServletResponse response) {
		if (token != null && !AuthController.TOKEN_MODE_JWT.equals(token)) {
			throw AuthController.unsupportedTokenMode();
		}
		if (link) {
			return link(body, token, principal);
		}
		if (token == null && BearerRequests.isBearer(request)) {
			throw badRequest("Bearer 요청은 ?token=jwt 로 로그인하세요");
		}
		String phone = verifiedPhone(body);
		User user = phoneIdentities.resolveForLogin(phone);
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new ApiException(HttpStatus.FORBIDDEN, "suspended", "정지된 계정은 로그인할 수 없습니다");
		}
		// B4 재활용 번호 보호: 다른 수단도 있는 계정이면 기존 수단으로 다시 인증하기 전까지 제한 세션·토큰이다.
		boolean stepUpRequired = identities.existsByUserIdAndProviderNot(user.getId(), AuthProvider.PHONE);
		if (token != null) {
			return tokens.issueForLogin(user, stepUpRequired);
		}
		sessionLogin.login(user, stepUpRequired, request, response);
		return UserSummary.of(user, identities.findProvidersByUserId(user.getId()), stepUpRequired);
	}

	private UserSummary link(PhoneVerifyRequest body, String token, AuthUser principal) {
		if (token != null) {
			throw badRequest("연결(link=true)은 token 과 같이 쓸 수 없습니다");
		}
		if (principal == null) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인한 뒤 연결할 수 있습니다");
		}
		if (principal.stepUpRequired()) {
			// 제한 세션이 새 수단을 붙여 그 수단으로 step-up 하는 우회를 막는다 (B4).
			throw StepUpRequiredException.apiException();
		}
		String phone = verifiedPhone(body);
		phoneIdentities.link(principal.id(), phone);
		User user = users.findById(principal.id())
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "인증이 필요합니다"));
		return summary(user);
	}

	private String verifiedPhone(PhoneVerifyRequest body) {
		Optional<String> phone = parse(body.challengeId()).flatMap(id -> otp.verify(id, body.code()));
		return phone.orElseThrow(
				() -> new ApiException(HttpStatus.UNAUTHORIZED, INVALID_OTP, "인증번호가 올바르지 않거나 만료되었습니다"));
	}

	private static Optional<UUID> parse(String challengeId) {
		try {
			return Optional.of(UUID.fromString(challengeId.strip()));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private UserSummary summary(User user) {
		return UserSummary.of(user, identities.findProvidersByUserId(user.getId()));
	}

	private static ApiException badRequest(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", message);
	}

}
