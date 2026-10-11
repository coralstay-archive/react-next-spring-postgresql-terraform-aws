package com.example.board.auth.identity;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.board.auth.local.LocalCredentialRepository;
import com.example.board.common.ApiException;
import com.example.board.user.AuthProvider;
import com.example.board.user.UserIdentity;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;

/** 내 로그인 수단 조회·해제 (TASK-4#4). */
@Service
public class IdentityService {

	public static final String LAST_IDENTITY = "last_identity";

	public static final String NOT_FOUND = "identity_not_found";

	private final UserRepository users;

	private final UserIdentityRepository identities;

	private final LocalCredentialRepository credentials;

	public IdentityService(UserRepository users, UserIdentityRepository identities,
			LocalCredentialRepository credentials) {
		this.users = users;
		this.identities = identities;
		this.credentials = credentials;
	}

	@Transactional(readOnly = true)
	public List<IdentityResponse> list(Long userId) {
		return identities.findByUserIdOrderByLinkedAtAscIdAsc(userId).stream().map(IdentityResponse::of).toList();
	}

	/**
	 * 내 identity 하나를 해제한다.
	 *
	 * <ul>
	 * <li>남의 것이나 없는 id 는 404 {@code identity_not_found} (존재 여부를 드러내지 않는다).</li>
	 * <li>B3: 마지막 하나는 해제할 수 없다 → 409 {@code last_identity} (로그인할 수단이 없는 계정이 생기지 않게).
	 * 같은 계정의 동시 해제 두 건이 둘 다 "아직 2개" 를 보고 지나가지 않도록 계정 행을 잠그고 센다.</li>
	 * <li>LOCAL 이면 비밀번호(local_credentials)도 지운다. (FK ON DELETE CASCADE 도 있지만 의도를 코드에 드러낸다.)</li>
	 * </ul>
	 */
	@Transactional
	public void unlink(Long userId, Long identityId) {
		users.findByIdForUpdate(userId).orElseThrow(IdentityService::notFound);
		UserIdentity identity = identities.findById(identityId)
			.filter(i -> i.getUser().getId().equals(userId))
			.orElseThrow(IdentityService::notFound);
		if (identities.countByUserId(userId) <= 1) {
			throw new ApiException(HttpStatus.CONFLICT, LAST_IDENTITY, "마지막 로그인 수단은 해제할 수 없습니다");
		}
		if (identity.getProvider() == AuthProvider.LOCAL) {
			credentials.findById(identity.getId()).ifPresent(credentials::delete);
		}
		identities.delete(identity);
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "로그인 수단을 찾을 수 없습니다");
	}

}
