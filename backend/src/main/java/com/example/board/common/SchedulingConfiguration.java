package com.example.board.common;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * 주기 작업(@Scheduled)을 켠다. 예: 미확정 첨부 정리 (TASK-11#4), 연결 해제·삭제 글 첨부 정리 (TASK-26#4).
 *
 * <p>분산 락 (TASK-26#5, BE-37): 인스턴스가 여럿이면 같은 작업이 동시에 돌 수 있으므로 ShedLock 으로 작업마다 락을 잡는다. 모든
 * {@code @Scheduled} 메서드에 {@code @SchedulerLock(name, lockAtMostFor, lockAtLeastFor)} 를 붙인다(SchedulerLockTest 가 검사한다). 락을 못
 * 잡은 실행은 아무것도 하지 않고 끝난다. 락은 같은 PostgreSQL 의 {@code shedlock} 테이블(V14)이고 시각은 DB 시계를 쓴다(인스턴스 시계가
 * 어긋나도 된다). {@code lockAtMostFor} 는 인스턴스가 죽어 락을 못 풀었을 때의 상한, {@code lockAtLeastFor}
 * ({@code app.scheduling.lock-at-least-for}, 기본 10초)는 아주 짧게 끝난 작업을 시계가 조금 다른 인스턴스가 바로 또 돌리지 않게 하는 최소 유지
 * 시간이다.
 *
 * <p>Spring Session JDBC 의 만료 세션 정리({@code JdbcIndexedSessionRepository} 자체 스케줄러, 매분)는 라이브러리가 관리하는 작업이라 이
 * 락 밖이다. 만료 행을 지우는 멱등 DELETE 라 여러 인스턴스가 겹쳐 돌아도 결과가 같다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingConfiguration {

	@Bean
	LockProvider lockProvider(DataSource dataSource) {
		return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
			.withJdbcTemplate(new JdbcTemplate(dataSource))
			.usingDbTime()
			.build());
	}

}
