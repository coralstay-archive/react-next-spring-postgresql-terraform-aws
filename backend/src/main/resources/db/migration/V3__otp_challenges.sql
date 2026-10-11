-- 휴대폰 OTP 챌린지 (TASK-7, decision-7). 인증번호 원문은 저장하지 않고 HMAC-SHA256(서버 키, 챌린지 id·번호·코드)만 둔다.
-- attempts: 틀린 검증 횟수. 최대치(기본 5)에 이르면 챌린지는 폐기된 것으로 본다(B2).
-- consumed_at: 검증에 성공한 시각. 값이 있으면 다시 쓸 수 없다(B1).
-- 발송 레이트리밋(번호·IP 별, B3)도 이 표의 created_at 으로 센다 → 인스턴스가 여러 개여도 같은 한도를 본다.
CREATE TABLE otp_challenges (
	id            UUID         PRIMARY KEY,
	phone         VARCHAR(32)  NOT NULL,
	code_hash     VARCHAR(128) NOT NULL,
	purpose       VARCHAR(20)  NOT NULL DEFAULT 'LOGIN',
	attempts      INT          NOT NULL DEFAULT 0,
	expires_at    TIMESTAMPTZ  NOT NULL,
	consumed_at   TIMESTAMPTZ,
	created_at    TIMESTAMPTZ  NOT NULL,
	requester_ip  VARCHAR(64),
	CONSTRAINT otp_challenges_purpose_chk CHECK (purpose IN ('LOGIN')),
	CONSTRAINT otp_challenges_attempts_chk CHECK (attempts >= 0)
);
CREATE INDEX otp_challenges_phone_created_ix ON otp_challenges (phone, created_at);
CREATE INDEX otp_challenges_ip_created_ix ON otp_challenges (requester_ip, created_at);
