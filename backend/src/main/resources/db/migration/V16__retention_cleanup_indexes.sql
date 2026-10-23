-- 보존 기간 정리 (TASK-27#1 BE-10). 정리 작업(RetentionCleanup)의 조건과 FK 확인이 표 전체를 훑지 않게 한다.
-- refresh_tokens: 이전 토큰 → 다음 토큰 FK(replaced_by). 토큰 행을 지울 때마다 PostgreSQL 이 "이 행을 가리키는 행"을 찾는다
CREATE INDEX refresh_tokens_replaced_by_ix ON refresh_tokens (replaced_by);
-- 만료·폐기 뒤 보존 기간이 지난 토큰 찾기
CREATE INDEX refresh_tokens_expires_at_ix ON refresh_tokens (expires_at);
CREATE INDEX refresh_tokens_revoked_at_ix ON refresh_tokens (revoked_at) WHERE revoked_at IS NOT NULL;
-- 만료 뒤 보존 기간이 지났고 발송 한도 창 밖인 OTP 챌린지 찾기
CREATE INDEX otp_challenges_created_ix ON otp_challenges (created_at);
