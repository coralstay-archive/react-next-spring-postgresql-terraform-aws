package com.example.board.auth.local;

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

	private final UserRepository users;

	private final UserIdentityRepository identities;

	private final LocalCredentialRepository credentials;

	private final PasswordEncoder passwordEncoder;

	public LocalAccountService(UserRepository users, UserIdentityRepository identities,
			LocalCredentialRepository credentials, PasswordEncoder passwordEncoder) {
		this.users = users;
		this.identities = identities;
		this.credentials = credentials;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * users + user_identities(LOCAL, 소문자 이메일) + local_credentials 를 한 트랜잭션으로 만든다.
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

	public static ApiException emailTaken() {
		return new ApiException(HttpStatus.CONFLICT, EMAIL_TAKEN, "이미 가입된 이메일입니다");
	}

}
