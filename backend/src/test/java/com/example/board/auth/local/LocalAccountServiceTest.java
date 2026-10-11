package com.example.board.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.example.board.common.ApiException;
import com.example.board.user.AuthProvider;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;

/** TASK-3#2 LocalAccountService 단위 테스트 (타이밍 균등화). */
class LocalAccountServiceTest {

	/** matches 호출을 기록하는 실제 DelegatingPasswordEncoder. */
	static final class RecordingEncoder implements PasswordEncoder {

		final PasswordEncoder delegate = PasswordEncoderFactories.createDelegatingPasswordEncoder();

		final List<String> matchedHashes = new ArrayList<>();

		@Override
		public String encode(CharSequence raw) {
			return delegate.encode(raw);
		}

		@Override
		public boolean matches(CharSequence raw, String encoded) {
			matchedHashes.add(encoded);
			return delegate.matches(raw, encoded);
		}

	}

	@Test
	@DisplayName("TASK-3#2 TASK-3#B2 없는 이메일이어도 더미 BCrypt 해시와 한 번 비교해 응답 시간을 맞춘다")
	void unknownEmailRunsDummyBcrypt() {
		UserIdentityRepository identities = mock(UserIdentityRepository.class);
		when(identities.findWithUserByProviderAndSubject(any(), any())).thenReturn(Optional.empty());
		RecordingEncoder encoder = new RecordingEncoder();
		LocalAccountService service = new LocalAccountService(mock(UserRepository.class), identities,
				mock(LocalCredentialRepository.class), encoder, new AdminBootstrap(identities, mock(UserRepository.class), ""));

		assertThatThrownBy(() -> service.authenticate("nobody@example.com", "passw0rd!"))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.getStatus().value()).isEqualTo(401);
				assertThat(ex.getError()).isEqualTo("invalid_credentials");
			});
		assertThat(encoder.matchedHashes).singleElement()
			.satisfies(hash -> assertThat(hash).startsWith("{bcrypt}$2"));
	}

	@Test
	@DisplayName("TASK-3#2 TASK-3#B2 로그인 키는 소문자로 정규화한 LOCAL 이메일이다")
	void lookupUsesNormalizedLocalSubject() {
		UserIdentityRepository identities = mock(UserIdentityRepository.class);
		when(identities.findWithUserByProviderAndSubject(AuthProvider.LOCAL, "mixed@example.com")).thenReturn(Optional.empty());
		LocalAccountService service = new LocalAccountService(mock(UserRepository.class), identities,
				mock(LocalCredentialRepository.class), new RecordingEncoder(),
				new AdminBootstrap(identities, mock(UserRepository.class), ""));

		assertThatThrownBy(() -> service.authenticate("  Mixed@Example.COM ", "x")).isInstanceOf(ApiException.class);
		org.mockito.Mockito.verify(identities).findWithUserByProviderAndSubject(AuthProvider.LOCAL, "mixed@example.com");
	}

}
