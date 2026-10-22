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
 * <p><b>기동 시에만, ADMIN 이 하나도 없을 때만</b> 올린다(1회성): 이미 그 LOCAL 계정이 있으면 ADMIN 으로 올린다. ADMIN 이 이미
 * 있으면 아무것도 하지 않는다 — 재시작마다 다시 올리면 첫 관리자가 LOCAL 을 해제한 뒤 같은 이메일로 가입한 사람이 ADMIN 이 된다. 가입 시 자동 승격은 하지 않는다(TASK-21#3,
 * doc-2 BE-14) — 이메일 소유 확인이 없으므로 실제 관리자보다 먼저 그 이메일로 가입한 사람이 ADMIN 이 될 수 있었다. 운영 절차:
 * (a) 관리자가 직접 가입 → (b) 운영자가 그 계정이 관리자 본인 것인지 확인 → (c) ADMIN_EMAIL 설정 + 재시작(재배포) →
 * (d) 승격 확인 뒤 ADMIN_EMAIL 을 비운다. 설정을 바꿔도 이전 ADMIN 을
 * 내리지는 않는다 — 강등은 관리자 API(TASK-12)로 한다.
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

	@Override
	public void run(ApplicationArguments args) {
		if (adminEmail == null) {
			return;
		}
		if (users.existsByRole(Role.ADMIN)) {
			// 1회성 (TASK-21#3 B2): 재시작(OOM·재부팅·다른 배포)마다 다시 올리면, 첫 관리자가 LOCAL 을 해제한 뒤 같은 이메일로
			// 가입한 다른 사람이 ADMIN 이 될 수 있다
			log.info("ADMIN 이 이미 있어 app.admin.email 부트스트랩을 건너뛴다. 승격이 끝났으면 ADMIN_EMAIL 을 비운다");
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
