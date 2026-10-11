-- 재활용 번호 보호 (TASK-7 B4): 다른 identity 가 있는 계정에 PHONE 으로 로그인해 받은 토큰은 제한 토큰이다.
-- refresh 로테이션으로 받은 access 토큰도 제한을 유지해야 하므로 refresh 토큰(=family)에 표시를 남긴다.
ALTER TABLE refresh_tokens ADD COLUMN step_up_required BOOLEAN NOT NULL DEFAULT false;
