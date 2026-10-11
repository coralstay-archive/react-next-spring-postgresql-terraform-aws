package com.example.board.auth.local;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.example.board.user.AuthProvider;
import com.example.board.user.Role;
import com.example.board.user.User;
import com.example.board.user.UserIdentityRepository;
import com.example.board.user.UserRepository;

/**
 * 첫 ADMIN 지정 (AC#6). {@code app.admin.email}(env {@code ADMIN_EMAIL})과 같은 이메일(대소문자 무시)의 <b>LOCAL</b> 계정이
 * ADMIN 이다.
 *
 * <ul>
 * <li>가입 시: {@link LocalAccountService#signup} 이 {@link #isAdminEmail} 로 확인해 ADMIN 으로 만든다.</li>
 * <li>기동 시: 이미 그 LOCAL 계정이 있으면 ADMIN 으로 올린다(멱등). 설정을 바꿔도 이전 ADMIN 을 내리지는 않는다 — 강등은
 * 관리자 API(TASK-12)로 한다.</li>
 * </ul>
 * 소셜·휴대폰 identity 의 이메일은 제공자가 검증을 보장하지 않으므로 보지 않는다. 비어 있으면 아무것도 하지 않는다.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

	private static final Log log = LogFactory.getLog(AdminBootstrap.class);

	private final UserIdentityRepository identities;

	private final UserRepository users;

	private final String adminEmail;

	@Autowired
	public AdminBootstrap(UserIdentityRepository identities, UserRepository users,
			@Value("${app.admin.email:}") String adminEmail) {
		this.identities = identities;
		this.users = users;
		String normalized = Emails.normalize(adminEmail);
		this.adminEmail = normalized == null || normalized.isEmpty() ? null : normalized;
	}

	/** 정규화된 이메일이 설정된 ADMIN 이메일인지. */
	public boolean isAdminEmail(String normalizedEmail) {
		return adminEmail != null && adminEmail.equals(normalizedEmail);
	}

	@Override
	public void run(ApplicationArguments args) {
		if (adminEmail == null) {
			return;
		}
		identities.findWithUserByProviderAndSubject(AuthProvider.LOCAL, adminEmail).ifPresent(identity -> {
			User user = identity.getUser();
			if (user.getRole() != Role.ADMIN) {
				user.changeRole(Role.ADMIN);
				users.save(user);
				log.info("app.admin.email 의 LOCAL 계정(user=" + user.getId() + ")을 ADMIN 으로 올렸다");
			}
		});
	}

}
