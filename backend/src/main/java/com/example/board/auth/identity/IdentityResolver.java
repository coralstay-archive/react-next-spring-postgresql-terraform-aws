package com.example.board.auth.identity;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.oauth.OAuth2UserInfo;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;
import com.example.board.user.UserIdentity;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;

/**
 * 외부 로그인 수단(provider, subject)을 계정으로 바꾼다 (doc-1 인증 설계, decision-4·8).
 *
 * <ul>
 * <li>로그인: (provider, subject) identity 가 있으면 그 계정, 없으면 새 계정 + identity 를 만든다.</li>
 * <li>이메일로 기존 계정을 찾지 않는다 — 같은 이메일의 LOCAL·다른 소셜 계정이 있어도 자동 병합하지 않는다 (B1).
 * 합치려면 로그인한 상태에서 명시적으로 연결한다 ({@link #link}).</li>
 * </ul>
 * 동시에 같은 새 identity 로 들어오면 UNIQUE(provider, subject) 가 막고, 진 쪽(DataIntegrityViolationException)은 호출자가
 * 다시 조회한다.
 */
@Service
public class IdentityResolver {

	private final UserRepository users;

	private final UserIdentityRepository identities;

	public IdentityResolver(UserRepository users, UserIdentityRepository identities) {
		this.users = users;
		this.identities = identities;
	}

	@Transactional
	public User resolveForLogin(OAuth2UserInfo info) {
		return identities.findWithUserByProviderAndSubject(info.provider(), info.subject())
			.map(UserIdentity::getUser)
			.orElseGet(() -> createAccount(info));
	}

	/**
	 * 연결 모드 (decision-8): 로그인한 사용자 {@code userId} 에 identity 를 붙인다. 이미 그 사용자의 것이면 그대로 둔다(false).
	 * 다른 계정의 것이면 {@link IdentityInUseException} (B2). 계정 정보(닉네임·이메일)는 바꾸지 않는다.
	 *
	 * @return 새로 붙였으면 true
	 */
	@Transactional
	public boolean link(Long userId, OAuth2UserInfo info) {
		Optional<UserIdentity> existing = identities.findWithUserByProviderAndSubject(info.provider(), info.subject());
		if (existing.isPresent()) {
			if (existing.get().getUser().getId().equals(userId)) {
				return false;
			}
			throw new IdentityInUseException();
		}
		User user = users.getReferenceById(userId);
		identities.saveAndFlush(UserIdentity.link(user, info.provider(), info.subject(), info.email()));
		if (info.provider() == AuthProvider.PHONE && user.getPhone() == null) {
			// 휴대폰 연결(TASK-7): 연락용 번호가 비어 있으면 채운다(이미 있으면 바꾸지 않는다).
			user.changePhone(info.subject());
		}
		return true;
	}

	/**
	 * 새 계정. 닉네임은 제공자 이름에서({@link Nicknames}), {@code users.email}(표시·연락용)은 제공자가 검증한 이메일일 때만
	 * 채운다. identity 에는 받은 이메일을 그대로(검증 여부와 무관) 기록한다. 역할은 USER (ADMIN_EMAIL 은 LOCAL 전용).
	 */
	private User createAccount(OAuth2UserInfo info) {
		User user = User.create(Nicknames.from(info.displayName()));
		if (info.emailVerified() && info.email() != null) {
			user.changeEmail(info.email());
		}
		if (info.provider() == AuthProvider.PHONE) {
			// 휴대폰 가입(TASK-7): subject 가 OTP 로 확인한 E.164 번호다.
			user.changePhone(info.subject());
		}
		users.save(user);
		identities.saveAndFlush(UserIdentity.link(user, info.provider(), info.subject(), info.email()));
		return user;
	}

}
