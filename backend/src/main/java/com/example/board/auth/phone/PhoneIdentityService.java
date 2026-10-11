package com.example.board.auth.phone;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.example.board.auth.identity.IdentityInUseException;
import com.example.board.auth.identity.IdentityResolver;
import com.example.board.auth.oauth.OAuth2UserInfo;
import com.example.board.user.AuthProvider;
import com.example.board.user.User;

/**
 * 검증된 번호(E.164)를 계정으로 바꾼다. 규칙은 소셜 로그인과 같은 {@link IdentityResolver} 를 쓴다: (PHONE, E.164) identity 가 있으면
 * 그 계정, 없으면 새 계정(+ users.phone). 다른 수단과 자동 병합하지 않는다(decision-8). 연결은 로그인한 계정에만.
 */
@Service
public class PhoneIdentityService {

	private final IdentityResolver resolver;

	public PhoneIdentityService(IdentityResolver resolver) {
		this.resolver = resolver;
	}

	public User resolveForLogin(String e164) {
		try {
			return resolver.resolveForLogin(info(e164));
		}
		catch (DataIntegrityViolationException ex) {
			// 같은 새 번호로 동시에 들어와 UNIQUE(provider, subject) 에 진 쪽: 이긴 쪽이 만든 계정으로 다시 찾는다.
			return resolver.resolveForLogin(info(e164));
		}
	}

	/** 로그인한 사용자에게 PHONE identity 를 붙인다. 다른 계정의 번호면 409 identity_in_use. */
	public void link(Long userId, String e164) {
		try {
			resolver.link(userId, info(e164));
		}
		catch (DataIntegrityViolationException ex) {
			throw new IdentityInUseException();
		}
	}

	/** 닉네임 후보는 비워 둔다(번호 일부라도 공개 닉네임에 드러나지 않게) → {@code user1234} 꼴. */
	private static OAuth2UserInfo info(String e164) {
		return new OAuth2UserInfo(AuthProvider.PHONE, e164, null, false, null);
	}

}
