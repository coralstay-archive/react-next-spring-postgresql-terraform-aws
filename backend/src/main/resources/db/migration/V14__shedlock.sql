-- TASK-26#5 (BE-37) 스케줄 작업 분산 락 ShedLock(JdbcTemplateLockProvider, usingDbTime). 작업 이름마다 한 행이고, lock_until 까지는
-- 다른 인스턴스가 같은 작업을 시작하지 않는다. 시각은 DB 시계(UTC, timestamp without time zone — ShedLock 의 PostgreSQL 기본 스키마).
CREATE TABLE shedlock (
	name       VARCHAR(64)  NOT NULL PRIMARY KEY,
	lock_until TIMESTAMP    NOT NULL,
	locked_at  TIMESTAMP    NOT NULL,
	locked_by  VARCHAR(255) NOT NULL
);
