package com.example.board.auth.local;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.common.ApiException;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentity;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;

/** LOCAL(이메일/비밀번호) 계정 가입·인증. */
@Service
public class LocalAccountService {

	public static final String EMAIL_TAKEN = "email_taken";

	public static final String INVALID_CREDENTIALS = "invalid_credentials";

	private final UserRepository users;

	private final UserIdentityRepository identities;

	private final LocalCredentialRepository credentials;

	private final PasswordEncoder passwordEncoder;

	/** 없는 계정에도 같은 비용의 BCrypt 비교를 하기 위한 더미 해시 (B2 타이밍 균등화). */
	private final String dummyHash;

	public LocalAccountService(UserRepository users, UserIdentityRepository identities,
			LocalCredentialRepository credentials, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.identities = identities;
		this.credentials = credentials;
		this.passwordEncoder = passwordEncoder;
		this.dummyHash = passwordEncoder.encode("timing-equalization-dummy-passw0rd");
	}

	/**
	 * users + user_identities(LOCAL, 소문자 이메일) + local_credentials 를 한 트랜잭션으로 만든다. 역할은 항상 USER 다 —
	 * {@code ADMIN_EMAIL} 과 같아도 가입만으로는 ADMIN 이 되지 않는다(TASK-21#3 B2, 승격은 기동 시 {@link AdminBootstrap}).
	 *
	 * <p>같은 이메일의 LOCAL identity 가 이미 있으면 409 (B1). 동시 가입 경쟁은 UNIQUE(provider, subject) 가 최종적으로 막고,
	 * 그 위반(트랜잭션 롤백 후의 DataIntegrityViolationException)은 호출자(AuthController)가 409 로 바꾼다.
	 */
	@Transactional
	public User signup(SignupRequest request) {
		String email = Emails.normalize(request.email());
		if (identities.findByProviderAndSubject(AuthProvider.LOCAL, email).isPresent()) {
			throw emailTaken();
		}
		User user = User.create(request.nickname());
		user.changeEmail(email);
		users.save(user);
		UserIdentity identity = identities.saveAndFlush(UserIdentity.link(user, AuthProvider.LOCAL, email, email));
		credentials.saveAndFlush(LocalCredential.of(identity.getId(), passwordEncoder.encode(request.password())));
		return user;
	}

	/**
	 * 이메일/비밀번호를 확인하고 계정을 돌려준다. 상태(SUSPENDED) 판정은 호출자가 한다 — 비밀번호가 맞은 뒤에만 정지 여부를
	 * 알려 주기 위해서다.
	 *
	 * <p>B2: 없는 이메일, LOCAL identity 가 없는 계정, 비밀번호 없음, 틀린 비밀번호, 72바이트 초과 입력은 모두 같은 401 이다.
	 * 계정이 없을 때도 더미 해시와 BCrypt 비교를 한 번 해서 응답 시간으로 계정 존재 여부가 드러나지 않게 한다.
	 */
	@Transactional(readOnly = true)
	public User authenticate(String email, String password) {
		Optional<UserIdentity> identity = identities.findWithUserByProviderAndSubject(AuthProvider.LOCAL,
				Emails.normalize(email));
		Optional<LocalCredential> credential = identity.flatMap(i -> credentials.findById(i.getId()));
		String raw = password == null ? "" : password;
		boolean matches = passwordEncoder.matches(raw, credential.map(LocalCredential::getPasswordHash).orElse(dummyHash));
		// BCrypt 는 검증 때 72바이트를 넘는 입력을 잘라서 비교하므로, 가입 정책(72바이트 이하)을 넘는 입력은 틀린 것으로 본다.
		boolean withinLimit = raw.getBytes(StandardCharsets.UTF_8).length <= PasswordPolicy.MAX_BYTES;
		if (credential.isEmpty() || !matches || !withinLimit) {
			throw invalidCredentials();
		}
		return identity.get().getUser();
	}

	/**
	 * step-up(TASK-7 B4): 이 계정의 LOCAL 비밀번호를 확인한다. LOCAL 이 없으면 빈 값, 틀리면 401 invalid_credentials
	 * (로그인과 같은 규칙: 72바이트 초과 입력도 틀린 것).
	 *
	 * @return LOCAL 이 있고 비밀번호가 맞으면 true, LOCAL 이 없으면 false
	 */
	@Transactional(readOnly = true)
	public boolean verifyPassword(Long userId, String password) {
		Optional<LocalCredential> credential = identities.findByUserId(userId)
			.stream()
			.filter(identity -> identity.getProvider() == AuthProvider.LOCAL)
			.findFirst()
			.flatMap(identity -> credentials.findById(identity.getId()));
		if (credential.isEmpty()) {
			return false;
		}
		String raw = password == null ? "" : password;
		if (!passwordEncoder.matches(raw, credential.get().getPasswordHash())
				|| raw.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES) {
			throw invalidCredentials();
		}
		return true;
	}

	public static ApiException invalidCredentials() {
		return new ApiException(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS, "이메일 또는 비밀번호가 올바르지 않습니다");
	}

	public static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, EMAIL_TAKEN, "이미 가입된 이메일입니다");
	}

}
