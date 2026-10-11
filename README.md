# 게시판 (Next.js + Spring Boot + PostgreSQL + Terraform/AWS)

> 초안. 배포·외부 설정 문서는 이후 태스크(TASK-18)에서 채운다.

Next.js 정적 export(S3 + CloudFront)와 Spring Boot API(EC2 + Docker Compose, us-east-1),
PostgreSQL로 만드는 게시판이다. 전체 설계는 [`backlog/docs/doc-1-architecture-plan.md`](backlog/docs/doc-1-architecture-plan.md),
설계 결정은 [`backlog/decisions/`](backlog/decisions/)에 있다.

## 구성

| 디렉토리 | 내용 |
|---|---|
| `backend/` | Spring Boot 4.1 · Java 25 · Gradle Kotlin DSL. 모든 API는 `/api` 아래 (`server.servlet.context-path=/api`) |
| `frontend/` | Next.js 16 · App Router · `output: 'export'` · TypeScript · Tailwind CSS v4 · shadcn/ui |
| `docker-compose.yml` | 로컬 PostgreSQL 18 + 첨부 저장소 MinIO(S3 호환, TASK-11) |
| `infra/` | Terraform 모듈(`infra/main/modules/cognito`, `uploads`) |
| `scripts/` | 로컬 `make`와 CI가 같이 호출하는 스크립트 (`scripts/test.sh` 등) |
| `backlog/` | backlog.md 태스크·결정·문서 |

Terraform 루트 스택, `deploy/`, `.github/workflows/`는 이후 태스크에서 추가한다.

## 준비물

- Java 25, Node.js 24, Docker (Docker Desktop 등)
- GNU Make

## 로컬 실행

```bash
# (선택) 환경변수 파일 — 복사하지 않아도 기본값으로 동작한다
cp example.env .env

make dev-db         # PostgreSQL 기동 (docker compose)
make dev-storage    # (선택) 첨부 저장소 MinIO 기동 + 버킷 준비 — 이미지 첨부를 쓸 때
make backend-run    # 백엔드 http://localhost:8080/api
make frontend-dev   # 프론트 http://localhost:3000
```

백엔드 상태 확인:

```bash
curl http://localhost:8080/api/actuator/health
# {"status":"UP", ...}
```

DB 스키마는 Flyway(`backend/src/main/resources/db/migration`)가 관리한다.
끝나면 `make dev-db-down`으로 DB와 MinIO를 내린다(데이터는 `pgdata`·`miniodata` 볼륨에 남는다).

로컬 첨부(MinIO): `make dev-storage`가 MinIO(API `http://localhost:9000`, 콘솔 `http://localhost:9001`, 계정 `minioadmin`/`minioadmin`)를
띄우고 one-shot 컨테이너 `minio-init`(mc)이 버킷 `board-uploads`를 만든 뒤 `uploads/` 아래를 익명 읽기로 연다.
백엔드는 `example.env`의 `UPLOADS_*` 값(복사한 `.env`)으로 MinIO를 쓴다. 이 값이 없으면(`UPLOADS_BUCKET` 비어 있음) 앱은 뜨지만
첨부 API는 503 `uploads_unavailable`이다. 로컬에서 첨부 `url`은 `UPLOADS_PUBLIC_BASE_PATH`(`http://localhost:9000/board-uploads/uploads/`)
기준이라 브라우저로 바로 열린다. CORS는 MinIO 전역 설정 `MINIO_API_CORS_ALLOW_ORIGIN`(= `PUBLIC_BASE_URL`, 기본 `http://localhost:3000`)으로
준다(MinIO는 버킷 CORS API를 구현하지 않는다). Docker Hub의 `minio/minio` 이미지는 더 이상 받을 수 없어 `cgr.dev/chainguard/minio`를 digest로 고정해 쓴다.

## 테스트

```bash
make test   # = scripts/test.sh : 리포 검증 + Terraform(fmt·test) + 백엔드(./gradlew test) + 프론트(npm test)
```

- 백엔드: JUnit 5 + Testcontainers(PostgreSQL, MinIO — 컨테이너는 각각 하나를 모든 통합 테스트가 같이 쓴다). **Docker가 떠 있어야 한다.**
- 프론트: Vitest + Testing Library.
- 리포: `scripts/test/repo_test.sh` (Makefile 타깃, hooks 설정, gitignore 등 골격 검증)
- Terraform: `terraform fmt -check -recursive infra` + `tests/*.tftest.hcl`이 있는 모듈마다 `terraform init -backend=false`·`validate`·`test`.
  테스트는 `mock_provider "aws"`라 AWS 자격 증명이 필요 없다(provider는 처음 한 번 내려받아 `~/.terraform.d/plugin-cache`에 둔다).
  `terraform`이 없으면 경고만 내고 건너뛴다. 재사용 모듈의 `.terraform.lock.hcl`은 커밋하지 않는다(루트 스택이 버전을 고정).

개별 실행: `make backend-test`, `make frontend-test`, 정적 export 빌드는 `make frontend-build`(결과물 `frontend/out/`).
전체 타깃 목록은 `make help`.

## 환경변수

`example.env` 참고. 실제 값은 git에 올리지 않는 `.env`에만 둔다. 운영 비밀값은 SSM Parameter Store에 두고
배포 스크립트가 EC2에서 주입한다(이후 태스크).

| 변수 | 기본값 | 용도 |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_PORT` | `board` / `board` / `board` / `5432` | 로컬 PostgreSQL 컨테이너 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/board` / `board` / `board` | 백엔드 DB 접속 |
| `JWT_SECRET` | 개발용 기본값(운영 금지) | 자체 서명 JWT HS256 키, 32바이트 이상이 아니면 기동 실패 |
| `COOKIE_SECURE` | `false` | `SESSION`·`XSRF-TOKEN` 쿠키 Secure 플래그(HTTPS 운영은 `true`) |
| `ADMIN_EMAIL` | (비어 있음) | 첫 ADMIN. 이 이메일(대소문자 무시)의 **LOCAL** 계정은 가입 시 ADMIN이 되고, 이미 있으면 기동 시 ADMIN으로 올린다 |
| `JWT_REFRESH_TTL` | `P14D` | refresh 토큰 수명(ISO-8601). access 토큰은 15분 |
| `PUBLIC_BASE_URL` | `http://localhost:3000` | 브라우저가 보는 공개 주소(끝 `/` 없이). OAuth redirect-uri와 로그인 후 프론트 리다이렉트를 이 값으로 만든다. 운영은 `https://<cloudfront 도메인 또는 커스텀 도메인>` |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | (비어 있음) | Google 로그인. 비우면 Google을 등록하지 않는다 |
| `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET` | (비어 있음) | GitHub 로그인. 비우면 GitHub를 등록하지 않는다 |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | (비어 있음) | Naver 로그인(Client ID / Client Secret). 비우면 등록하지 않는다 |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | (비어 있음) | Kakao 로그인(REST API 키 / 보안 → Client Secret). 비우면 등록하지 않는다 |
| `COGNITO_CLIENT_ID` / `COGNITO_CLIENT_SECRET` | (비어 있음) | Cognito 로그인(Terraform 출력 `client_id` / `client_secret`). 비우면 등록하지 않는다 |
| `COGNITO_ISSUER_URI` / `COGNITO_DOMAIN` | (비어 있음) | Terraform 출력 `issuer_uri` / `domain_url`. `COGNITO_CLIENT_ID`가 있으면 둘 다 필요(없으면 기동 실패) |
| `SMS_PROVIDER` | `console` | SMS 발송 구현. `console`(로그로만 남김, 로컬 전용) · `memory`(테스트) · `aws`(End User Messaging SMS). 그 밖의 값이면 기동 실패 |
| `SMS_DEFAULT_REGION` | `KR` | `+` 없이 들어온 번호를 해석할 지역(E.164 정규화) |
| `SMS_AWS_REGION` / `SMS_ORIGINATION_IDENTITY` / `SMS_CONFIGURATION_SET` | `us-east-1` / (비어 있음) / (비어 있음) | `SMS_PROVIDER=aws` 일 때 리전, 발신 identity(발신 번호·sender ID·pool ARN, 비우면 계정 기본), configuration set(선택) |
| `OTP_HASH_KEY` | 개발용 기본값(운영 금지) | OTP 코드 해시(HMAC-SHA256) 키, 32바이트 이상이 아니면 기동 실패 |
| `OTP_PHONE_DAILY_LIMIT` / `OTP_IP_HOURLY_LIMIT` | `10` / `20` | OTP 발송 한도: 같은 번호 24시간 N회, 같은 클라이언트 IP 1시간 M회(같은 번호 60초 1회는 고정) |
| `CLIENT_IP_HEADER` | (비어 있음) | 클라이언트 IP를 읽을 신뢰 헤더. 비우면 remote address. CloudFront 뒤(운영)는 `CloudFront-Viewer-Address` |
| `UPLOADS_BUCKET` | (비어 있음) | 첨부 버킷(TASK-11). 비우면 S3 클라이언트를 만들지 않고 첨부 API는 503 `uploads_unavailable`(앱은 그대로 뜬다). 운영: Terraform `uploads` 모듈 출력 `bucket_name` |
| `UPLOADS_REGION` | `ap-northeast-2` | 버킷 리전(운영은 버킷이 있는 리전으로 반드시 지정. 로컬 MinIO는 `us-east-1`) |
| `UPLOADS_ENDPOINT` / `UPLOADS_PATH_STYLE` | (비어 있음) / `false` | S3 호환 엔드포인트와 path-style 주소. 로컬 MinIO는 `http://localhost:9000` / `true`, 운영 S3는 비움 / `false` |
| `UPLOADS_ACCESS_KEY` / `UPLOADS_SECRET_KEY` | (비어 있음) | 정적 자격 증명(로컬 MinIO 전용). 비우면 SDK 기본 체인(EC2 인스턴스 프로파일) |
| `UPLOADS_PUBLIC_BASE_PATH` | `/uploads/` | 첨부 공개 URL 앞부분. url = 이 값 + 키에서 `uploads/` 뒤. 운영은 CloudFront `/uploads/*`(같은 오리진), 로컬은 `http://localhost:9000/board-uploads/uploads/` |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` / `MINIO_PORT` / `MINIO_CONSOLE_PORT` | `minioadmin` / `minioadmin` / `9000` / `9001` | 로컬 MinIO 컨테이너 |

## 인증·인가 모델 (백엔드)

- **계정**: `users` 1 : N `user_identities(provider, subject)`. 비밀번호는 `local_credentials`에만 있다(decision-4).
- **하나의 보안 체인**(decision-5): `Authorization: Bearer <JWT>`가 있으면 JWT로 인증하고 세션은 읽지도 쓰지도 않는다.
  없으면 `SESSION` 쿠키(Spring Session JDBC, HttpOnly·SameSite=Lax)로 인증한다. 토큰이 틀리면 세션이 있어도 401.
- **CSRF**: 세션 요청의 POST/PUT/PATCH/DELETE는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보내야 한다.
  SPA는 처음에 `GET /api/auth/csrf`로 쿠키를 받는다. Bearer 요청은 CSRF 면제.
- **오류**: 미인증 401, 권한 없음 403, 모두 JSON(`{"status","error","message"}`). 로그인 페이지 리다이렉트는 없다.
- **인가**: 기본은 인증 필요. 역할 `USER`/`ADMIN`은 `@PreAuthorize`, 수정·삭제는 `@ownership.canModify(authentication, ownerId)`
  (본인 또는 ADMIN). `SUSPENDED` 계정은 읽기만 되고 모든 쓰기가 403(DB 최신 상태 기준, 보안 체인에서 공통 처리).
- **제한 세션**(재활용 번호 보호, 아래 휴대폰 로그인): 읽기만 되고 쓰기는 403 `step_up_required`(로그아웃·step-up·로그인 계열 제외).
- `/api/actuator/health`는 공개, 세부 정보(DB 등)는 ADMIN에게만.

### 인증 API (LOCAL)

| 요청 | 설명 |
|---|---|
| `POST /api/auth/signup` `{email, password, nickname}` | 201 + 사용자 요약. 자동 로그인 없음. 비밀번호 8자 이상·72바이트 이하·문자+숫자, 닉네임 2~20자. 같은 이메일(대소문자 무시) 중복은 409. CSRF 필요 |
| `POST /api/auth/login` `{email, password}` | 세션 로그인: 세션 ID 교체 + `SESSION` 쿠키 + 새 `XSRF-TOKEN` 쿠키, 200 + 사용자 요약. CSRF 필요 |
| `POST /api/auth/login?token=jwt` | 세션 없이 `{accessToken, refreshToken, tokenType:"Bearer", expiresIn}`. CSRF 면제(쿠키를 만들지 않는 비브라우저 흐름) |
| `POST /api/auth/token/refresh` `{refreshToken}` | 로테이션: 새 access+refresh. 이미 쓴 refresh를 다시 쓰면 그 로그인(family)의 토큰을 모두 폐기하고 401. Authorization 헤더 없이 호출. CSRF 면제 |
| `POST /api/auth/logout` | 세션: 세션 삭제 + 쿠키 만료(CSRF 필요). Bearer: 본문 `{refreshToken}`이 있으면 그 family 폐기. 항상 204, 정지 계정도 가능 |
| `GET /api/auth/me` | 현재 사용자 `{id, nickname, email, role, status, providers, stepUpRequired, stepUpProviders}` (DB 최신 값). 익명은 401. `stepUpRequired`면 이 세션·토큰은 제한 상태이고 `stepUpProviders`는 다시 인증할 수 있는 수단 |

- 로그인 실패는 이유와 상관없이 같은 401(`invalid_credentials`)이다. 정지 계정은 비밀번호가 맞을 때만 403(`suspended`).
- refresh 토큰은 256비트 랜덤 값이고 DB에는 SHA-256 해시만 둔다. access 토큰은 상태가 없어 로그아웃 뒤에도 만료(15분)까지 유효하다.

### 휴대폰 OTP 로그인 (TASK-7)

번호 + 인증번호로 하는 비밀번호 없는 로그인·가입이다(provider=`PHONE`, subject=E.164 번호, decision-7).

| 요청 | 설명 |
|---|---|
| `POST /api/auth/phone/otp` `{phone}` | 202 `{challengeId, expiresIn:180}` + 6자리 인증번호 SMS. 계정이 있든 없든 같은 응답. 공개, CSRF 필요(Bearer는 면제) |
| `POST /api/auth/phone/verify` `{challengeId, code}` | 맞으면 (PHONE, 번호) 계정으로 로그인, 없으면 새 계정(+ `users.phone`). 세션 로그인(세션 ID 교체 + 쿠키), 200 + 사용자 요약. CSRF 필요 |
| `POST /api/auth/phone/verify?token=jwt` | 세션 없이 access+refresh. CSRF 면제(`login?token=jwt`와 같은 이유) |
| `POST /api/auth/phone/verify?link=true` | (로그인 필요, 세션·Bearer) 현재 계정에 PHONE 연결 + 비어 있으면 `users.phone`, 200 + 사용자 요약. 다른 계정의 번호면 409 `identity_in_use`. 세션이면 CSRF 필요(`token`과 같이 쓸 수 없다) |
| `POST /api/auth/step-up/local` `{password[, refreshToken]}` | (로그인 필요) 제한 세션·토큰을 비밀번호로 풀기. 세션: 세션 ID를 바꾼 제한 없는 세션. Bearer: 제한 없는 새 토큰(본문 refreshToken의 family 폐기). 틀리면 401 `invalid_credentials`, LOCAL이 없으면 400 `step_up_unavailable` |

- 번호는 libphonenumber로 E.164로 정규화한다(`+` 없으면 `SMS_DEFAULT_REGION`=KR). 형식이 틀리면 400 `invalid_phone`.
- 인증번호는 SecureRandom 6자리이고 DB(`otp_challenges`)에는 HMAC-SHA256(`OTP_HASH_KEY`, 챌린지 id·번호·코드)만 둔다.
  **3분** 뒤 만료되고, 한 번 성공하면 소멸한다(재사용 불가). 틀린 시도가 **5번**이 되면 챌린지를 폐기한다(그 뒤에는 맞는 코드도 실패).
  시도 횟수는 챌린지 행을 잠그고(FOR UPDATE) 세므로 동시 요청에도 정확하다.
- 검증 실패(틀린 코드, 없음·만료·소멸·폐기된 챌린지)는 모두 같은 401 `invalid_otp`. 정지 계정은 403 `suspended`.
- **발송 한도**(DB 기준이라 인스턴스가 여러 개여도 같다): 같은 번호 60초에 1회·24시간 10회(`OTP_PHONE_DAILY_LIMIT`), 같은 클라이언트 IP
  1시간 20회(`OTP_IP_HOURLY_LIMIT`). 넘으면 429 `rate_limited` + `Retry-After`(초). SMS 발송 실패는 503 `sms_unavailable`.
- **클라이언트 IP**: CloudFront 뒤에서는 EC2가 CloudFront의 IP를 보므로 `CLIENT_IP_HEADER=CloudFront-Viewer-Address`로 두고
  그 값(`ip:port`)의 IP를 쓴다. 헤더가 없거나 형식이 틀리면 remote address. `X-Forwarded-For`는 쓰지 않는다(클라이언트가 위조 가능).
  CloudFront 오리진 요청 정책이 `CloudFront-Viewer-Address`를 넘겨야 하고, EC2는 CloudFront를 거친 요청만 받아야 한다(TASK-16).
- **재활용 번호 보호**: 번호는 해지 후 다른 사람에게 다시 배정될 수 있다. 그래서 PHONE 말고 다른 수단(LOCAL·소셜·Cognito)도 있는
  계정에 PHONE으로 로그인하면 **제한 세션·토큰**(`stepUpRequired=true`, JWT 클레임 `step_up_required`, refresh로 받은 토큰도 유지)이다.
  읽기는 되지만 쓰기는 403 `step_up_required`이고, 로그아웃·step-up·다른 로그인만 된다. 수단 연결(`?link=true`,
  `/api/me/identities/link/{p}`)과 해제도 막힌다. 풀려면 기존 수단으로 다시 인증한다: LOCAL은 `POST /api/auth/step-up/local`,
  소셜·Cognito는 그 수단으로 다시 로그인(`/api/oauth2/authorization/{p}`) — 같은 계정으로 들어오므로 제한 없는 새 세션이 된다.
  PHONE만 있는 계정은 제한하지 않는다.
- **SMS 설정**: 로컬은 `SMS_PROVIDER=console`(기본) — 인증번호가 백엔드 로그에 `[SMS console] to=... message=...`로 찍힌다.
  운영은 `SMS_PROVIDER=aws` + `SMS_AWS_REGION`(+ `SMS_ORIGINATION_IDENTITY`). AWS End User Messaging SMS
  (`pinpoint-sms-voice-v2` `SendTextMessage`, 메시지 유형 TRANSACTIONAL)를 EC2 인스턴스 프로파일 자격으로 부른다 —
  IAM에 `sms-voice:SendTextMessage`가 필요하다. 샌드박스 해제와 한국행 발신 등록(발신 번호·sender ID)은 콘솔 작업이다(TASK-18).

### 소셜 로그인 (Google · GitHub · Naver · Kakao · Cognito) 과 계정 연결

브라우저 내비게이션 흐름이다(fetch가 아니라 `location.href`로 이동한다). 모든 리다이렉트는 `PUBLIC_BASE_URL` 기준 절대 URL이다.

| 요청 | 설명 |
|---|---|
| `GET /api/oauth2/authorization/{google\|github\|naver\|kakao\|cognito}` | 로그인 시작 → 제공자 인가 화면으로 302. state·nonce는 세션(Spring Session JDBC)에 저장 |
| `GET /api/login/oauth2/code/{google\|github\|naver\|kakao\|cognito}` | 제공자 콜백. 성공: 세션 ID 교체 + `SESSION`·새 `XSRF-TOKEN` 쿠키 + 302 `/auth/callback/?status=ok`. 실패: 302 `/auth/callback/?error=<코드>` |
| `GET /api/me/identities/link/{google\|github\|naver\|kakao\|cognito}` | (로그인 필요, 세션 전용) 현재 계정에 연결 시작. 콜백 뒤 302 `/account/?linked=<제공자>` 또는 `/account/?error=<코드>` |
| `GET /api/me/identities` | 내 로그인 수단 `[{id, provider, email, linkedAt}]` |
| `DELETE /api/me/identities/{id}` | 해제, 204. 남의 것 404, 마지막 하나는 409 `last_identity`. LOCAL을 해제하면 비밀번호도 지운다. CSRF 필요 |

- 처음 보는 (제공자, subject)면 새 계정을 만든다. **이메일이 같아도 기존 계정에 합치지 않는다**(decision-8). 합치려면
  기존 계정으로 로그인한 뒤 "연결"한다. 이미 다른 계정에 붙은 수단은 연결할 수 없다(`identity_in_use`).
- 새 계정의 닉네임은 제공자 이름(GitHub는 이름이 없으면 login)을 2~20자로 정리한 값, `users.email`은 제공자가 검증한
  이메일일 때만 채운다(GitHub 공개 이메일은 검증된 주소로 본다. 비공개면 비어 있다). email은 표시·연락용이고 로그인 키가 아니다.
  - Naver: subject=`response.id`, 닉네임은 별명 → 이름. 이메일은 검증 여부 필드가 없지만 네이버 기본 이메일 또는 인증을 거친
    연락처 이메일이라 검증된 것으로 본다(미동의면 비어 있다).
  - Kakao: subject=숫자 `id`, 닉네임은 `kakao_account.profile.nickname` → `properties.nickname`. 이메일은 선택 동의라 없어도
    가입된다(`users.email`·identity email 모두 null). `is_email_valid`와 `is_email_verified`가 모두 true일 때만 기록한다.
  - Cognito(OIDC): subject=`sub`, 닉네임은 `name` → `preferred_username` → `given_name` → 이메일 앞부분. `email_verified`는
    boolean 또는 문자열 `"true"`(Cognito userinfo) 모두 받는다.
- 실패 코드: `access_denied`(사용자가 거부), `authorization_request_not_found`(state 없음·만료·재사용), `invalid_id_token`,
  `invalid_nonce`, `invalid_grant`, `invalid_token_response`(토큰 응답에 access_token 없음 — Naver 토큰 오류), `invalid_user_info_response`, `invalid_user_info`(제공자 응답에 id 없음), `unknown_provider`(설정 안 된 제공자), `suspended`,
  연결 전용 `identity_in_use`, `link_expired`(연결 시작 10분 초과 또는 그 사이 다른 계정으로 바뀜).

**제공자 콘솔 설정** — Authorized redirect URI(callback URL)에 다음을 그대로 넣는다(`PUBLIC_BASE_URL`이 바뀌면 모든 콘솔에서 바꾼다):

```
${PUBLIC_BASE_URL}/api/login/oauth2/code/google   # Google Cloud Console → OAuth 클라이언트 ID(웹 애플리케이션)
${PUBLIC_BASE_URL}/api/login/oauth2/code/github   # GitHub → Settings → Developer settings → OAuth Apps
${PUBLIC_BASE_URL}/api/login/oauth2/code/naver    # Naver Developers → 애플리케이션 → API 설정 → 네이버 로그인 Callback URL
${PUBLIC_BASE_URL}/api/login/oauth2/code/kakao    # Kakao Developers → 앱 → 카카오 로그인 → Redirect URI
${PUBLIC_BASE_URL}/api/login/oauth2/code/cognito  # Terraform cognito 모듈이 App Client callback_urls 로 등록(콘솔 작업 없음)
```

- **Naver**: Naver Developers에서 애플리케이션 등록(사용 API: 네이버 로그인). "제공 정보 선택"에서 별명(또는 이름)과 이메일을
  고른다(scope는 보내지 않고 이 설정을 따른다). 서비스 URL은 `PUBLIC_BASE_URL`. 검수 전에는 등록한 테스터 아이디만 로그인된다.
  토큰 요청은 client_id·client_secret을 파라미터로 보낸다(`client_secret_post`).
- **Kakao**: Kakao Developers에서 앱 생성 → 플랫폼(Web) 사이트 도메인에 `PUBLIC_BASE_URL` 등록 → 카카오 로그인 활성화 →
  Redirect URI 등록 → 보안에서 Client Secret 사용(코드 받아 `KAKAO_CLIENT_SECRET`, `client_secret_post`). `KAKAO_CLIENT_ID`는
  **REST API 키**. 동의 항목에 닉네임(`profile_nickname`)과 카카오계정 이메일(`account_email`, 선택 동의)을 설정한다 — 앱은
  `scope=profile_nickname,account_email`을 요청하므로 둘 중 하나라도 설정돼 있지 않으면 Kakao가 KOE205로 거부한다. 이메일
  동의 항목은 비즈 앱 전환이 필요할 수 있다.
- **Cognito**(decision-6, OIDC 제공자 하나로 취급): `infra/main/modules/cognito`가 User Pool(이메일 로그인·검증), secret이 있는
  App Client(code flow, `openid email profile`, 콜백 `${public_base_url}/api/login/oauth2/code/cognito`), Hosted UI 도메인을 만든다.
  출력 → env: `client_id` → `COGNITO_CLIENT_ID`, `client_secret`(sensitive, `terraform output -raw client_secret`) →
  `COGNITO_CLIENT_SECRET`, `issuer_uri`(`https://cognito-idp.<region>.amazonaws.com/<pool id>`) → `COGNITO_ISSUER_URI`,
  `domain_url`(`https://<prefix>.auth.<region>.amazoncognito.com`) → `COGNITO_DOMAIN`. 앱은 issuer 디스커버리를 하지 않고
  Hosted UI 도메인의 `/oauth2/authorize`·`/oauth2/token`·`/oauth2/userInfo`와 `<issuer>/.well-known/jwks.json`을 직접 쓴다
  (Cognito 없이도 앱이 뜬다). 사용자는 Hosted UI(관리형 로그인 페이지)에서 가입·이메일 인증·로그인한다. 로컬에서 시험하려면
  모듈의 `callback_urls`에 `http://localhost:3000/api/login/oauth2/code/cognito`를 더한다(Cognito는 localhost만 http 허용).

로컬 개발은 `PUBLIC_BASE_URL=http://localhost:3000`(Next 개발 서버가 `/api`를 백엔드로 프록시)이라 콘솔에도
`http://localhost:3000/api/login/oauth2/code/{google|github|naver|kakao|cognito}`를 등록한다. 프론트 프록시 없이 백엔드만 시험할 때는
`PUBLIC_BASE_URL=http://localhost:8080`으로 두고 그 주소의 콜백을 등록한다. 앱이 CloudFront 뒤에 있어 요청 Host로는 공개 주소를
알 수 없으므로 redirect-uri를 설정값으로 고정한다. client id/secret이 없는 제공자는 등록하지 않으며 앱은 그대로 뜬다.
새 제공자는 `OAuth2UserInfoMapper` 빈 하나와(Naver·Kakao가 비표준 응답 예시, Cognito가 설정으로 엔드포인트를 만드는 예시) `app.oauth2.registrations.<id>.*` 설정으로 붙인다.

## 게시판 API (TASK-8)

글 목록·상세·카테고리·태그 읽기는 공개(익명 가능)다. 쓰기는 로그인이 필요하고, 세션이면 CSRF 토큰도 필요하다. 정지 계정과 제한 세션의 쓰기는 공통 필터가 403으로 막는다.

| 요청 | 설명 |
|---|---|
| `GET /api/posts?category=<slug>&tag=<name>&q=<검색어>&page=0&size=20&sort=latest` | `{items:[{id, title, excerpt, author:{id, nickname}, category:{id, slug, name}\|null, tags, createdAt, updatedAt, deleted, hidden, likeCount, viewCount}], page, size, totalElements, totalPages}`. 오프셋 페이징. `size` 1~50(기본 20), `page` ≥ 0. `sort`는 `latest`(기본, `created_at`·`id` 내림차순)와 `popular`(TASK-9: `like_count` 내림차순, 같으면 최신순)만 받고 대소문자는 가리지 않는다. 범위를 벗어나거나 형식이 틀리면 400. `tag`는 태그와 같은 규칙으로 정규화한다. `excerpt`는 공백을 접은 본문 앞 200자이고, 잘렸으면 끝에 `…`를 붙인다 |
| `GET /api/posts/{id}` | `{id, title, content, author, category, tags, createdAt, updatedAt, editable, deleted, hidden, likeCount, likedByMe, viewCount, attachments}`. `attachments`는 붙은 이미지 `[{id, url, contentType, size, status}]`(올린 순, 아래 TASK-11). `editable`은 보는 사람이 작성자나 ADMIN이고 글이 삭제되지 않았을 때 true. `likedByMe`는 보는 사람이 좋아요를 눌렀는지(익명은 false). 조회수를 올린다(아래 TASK-9). 없으면 404 `post_not_found` |
| `POST /api/posts` `{title, content, categorySlug?, tags?, attachmentIds?}` | 201 + `Location` + 상세. `attachmentIds`(최대 10)는 본인이 올려 확정한 첨부(아래 TASK-11). 제목은 1~100자, 본문은 1~20000자이고 둘 다 공백만으로는 쓸 수 없다(어기면 400 `validation`). 없는 카테고리는 400 `unknown_category` |
| `PATCH /api/posts/{id}` | 보낸 필드만 바꾼다(빠진 필드나 null은 그대로 둔다). `categorySlug:""`는 카테고리를 빼고, `tags:[]`는 태그를 모두 지운다. `attachmentIds`는 첨부 목록을 통째로 바꾸고(빠진 첨부는 연결 해제), `[]`는 모두 뗀다. 200 + 상세 |
| `DELETE /api/posts/{id}` | soft delete(`deleted_at`). 204 |
| `GET /api/categories` | `[{id, slug, name, sortOrder, adminOnly}]`(sort_order 순). 기본 카테고리는 `notice`(공지, ADMIN 전용)와 `free`(자유게시판) |
| `GET /api/tags?q=<접두어>&limit=20` | `[{name, postCount}]`. 보이는 글에 붙은 태그만, 글 수 내림차순(같으면 이름순)이다. `q`는 소문자와 공백을 정규화한 접두어이고 `%`·`_`도 글자 그대로 비교한다. `limit`은 1~100 |

- **수정·삭제 권한(B1)**: 작성자와 ADMIN만 할 수 있다. `@PreAuthorize("@ownership.canModify(authentication, @postService.ownerOf(#id, authentication))")`로 검사하며, 글이 없거나 이미 삭제됐으면 404를 먼저 낸다. 숨긴 글은 ADMIN이 아니면 없는 글처럼 404다. 그다음 남의 글이면 403 `forbidden`이다. 삭제된 글은 ADMIN도 수정할 수 없다(복구는 TASK-12).
- **삭제·숨김 글(B2)**: ADMIN이 아니면 목록에 나오지 않고 상세는 404다. ADMIN은 상세에서 `deleted`·`hidden` 표시와 함께 볼 수 있고, 목록은 `?includeHidden=true`를 붙였을 때만 포함한다(ADMIN이 아니면 이 파라미터는 무시한다).
- **태그(B3)**: 이름은 NFC로 정규화한 뒤 앞뒤 공백을 지우고, 안쪽 공백을 한 칸으로 줄이고, 소문자로 바꾼다. 30자를 넘거나 비면 400 `invalid_tag`다. 한 글 안의 중복은 하나로 합치며, 합친 뒤 5개를 넘으면 400 `too_many_tags`다(입력 배열은 20개까지 받는다). 태그는 `INSERT ... ON CONFLICT (name) DO NOTHING`으로 넣은 뒤 다시 읽어서 재사용하므로, 같은 새 태그를 동시에 써도 행은 하나만 생긴다.
- **ADMIN 전용 카테고리**: `categories.admin_only=true`인 카테고리(공지)에 글을 쓰거나 글을 옮기는 것은 ADMIN만 할 수 있다. USER는 403 `category_admin_only`를 받는다.

### 댓글 · 좋아요 · 조회수 (TASK-9)

댓글 목록은 공개다. 댓글 작성·삭제와 좋아요·취소는 로그인이 필요하고, 세션이면 CSRF 토큰도 필요하다. 정지 계정과 제한 세션은 403(`suspended`·`step_up_required`)이다.

| 요청 | 설명 |
|---|---|
| `GET /api/posts/{id}/comments?page=0&size=20` | `{items:[{id, postId, content, author:{id, nickname}, createdAt, updatedAt, deleted, editable}], page, size, totalElements, totalPages}`. 오래된 순(`created_at`·`id` 오름차순). `size` 1~100(기본 20). 글이 없거나 보는 사람에게 보이지 않으면(삭제·숨김, ADMIN 제외) 404 `post_not_found` |
| `POST /api/posts/{id}/comments` `{content}` | 201 + `Location: /api/comments/{id}` + 댓글. 본문 1~2000자, 공백만은 400 `validation`. 삭제·숨김 글에는 ADMIN도 쓸 수 없다(404 `post_not_found`, B3) |
| `DELETE /api/comments/{id}` | 작성자 또는 ADMIN. soft delete, 204. 없거나 이미 삭제됐거나 글이 보이지 않으면 404 `comment_not_found`, 남의 댓글이면 403 `forbidden` |
| `POST /api/posts/{id}/like` | 멱등. 200 `{liked:true, likeCount}`. 이미 눌렀어도 200이고 수는 그대로다 |
| `DELETE /api/posts/{id}/like` | 멱등. 200 `{liked:false, likeCount}`. 삭제·숨김·없는 글이면 좋아요·취소 모두 404 `post_not_found` |

- **삭제된 댓글 표시**: 목록에서 빼지 않고 자리에 남긴다. `deleted:true`이고 `content`·`author`는 null, `editable`은 false다. 프론트는 "삭제된 댓글입니다"로 보여 준다. 그래서 페이지가 밀리지 않고 대화 순서가 유지된다. 댓글 수정 API는 없다.
- **좋아요 정합성(B1)**: `post_likes`의 PK `(post_id, user_id)`로 사용자당 글 하나에 1회만 누를 수 있다. `posts.like_count`는 `INSERT ... ON CONFLICT DO NOTHING`이나 `DELETE`가 실제로 바꾼 행이 1개일 때만 같은 트랜잭션에서 ±1 한다. 그래서 같은 요청이 동시에 와도 카운터는 행 수와 같다. 엔티티는 카운터를 읽기 전용으로 매핑하므로 글 수정이 카운터를 덮어쓰지 않는다.
- **조회수(B2)**: `GET /api/posts/{id}`가 보이는 글(삭제·숨김이 아닌 글)을 돌려줄 때 센다. 같은 방문자는 30분에 한 번만 센다. 방문자 키는 로그인 사용자면 `u:<user id>`(기기·세션과 상관없음)이다. 익명이면 `VID` 쿠키(128비트 랜덤, HttpOnly, SameSite=Lax, Path=/, 30일, `COOKIE_SECURE`를 따름)의 SHA-256인 `s:<hex>`이며, 익명에게 HTTP 세션은 만들지 않는다. `post_views (post_id, viewer_key, viewed_at)`에 `INSERT ... ON CONFLICT DO UPDATE ... WHERE viewed_at <= now-30m`를 실행해서 바뀐 행이 1개일 때만 `view_count + 1` 한다. 상세 읽기 트랜잭션이 끝난 뒤 별도 트랜잭션에서 실행한다. 응답의 `viewCount`에는 이번 조회가 들어 있다.
  - 한계: 쿠키를 받지 않는 클라이언트(봇 등)는 요청할 때마다 새 방문자로 센다. `post_views`는 계속 커지므로 30분이 지난 행을 지우는 정리 작업이 필요하다(후속 작업, `post_views_viewed_at_ix` 인덱스가 있다).
- **인기순**: `sort=popular`는 `like_count DESC, created_at DESC, id DESC` 순서다. 보이는 글만 담은 부분 인덱스 `posts_visible_popular_ix`를 쓴다.

### 검색 (TASK-10)

`GET /api/posts?q=<검색어>`는 제목과 본문을 찾는다. `category`·`tag`·`sort`·`includeHidden`(ADMIN)·페이징과 AND로 함께 쓰고, 삭제·숨김 글은 검색해도 ADMIN이 아니면 나오지 않는다. 정렬은 그대로 `sort`를 따른다(기본 최신순).

- **검색어 규칙**: 앞뒤 공백을 자른 뒤 2~100자(코드 포인트)여야 하고, 아니면 400 `validation`이다. 비었거나 공백뿐이면 검색 없이 목록을 돌려준다.
- **조건**: `search_tsv @@ websearch_to_tsquery('simple', q) OR title ILIKE '%q%' OR content ILIKE '%q%'`. 앞쪽은 단어 단위 전문 검색이다. websearch 문법을 따르므로 여러 단어는 AND이고, `"문구"`, `-제외어`, `or`를 쓸 수 있으며 짝이 맞지 않는 따옴표도 오류가 나지 않는다. 뒤쪽은 부분 일치다. ILIKE 패턴에서 `%`·`_`·`\`는 이스케이프해서 글자 그대로 찾는다. 검색어는 모두 바인드 파라미터로 넘긴다.
- **스키마**: `posts.search_tsv`는 `setweight(to_tsvector('simple', title), 'A') || setweight(to_tsvector('simple', content), 'B')`인 STORED 생성 컬럼(V7)이고 GIN 인덱스 `posts_search_tsv_ix`가 있다. 태스크 AC에는 "갱신 트리거"라고 적혀 있지만 생성 컬럼으로 대신했다. PostgreSQL이 INSERT·UPDATE 때 값을 다시 계산하므로 트리거 함수가 필요 없다.
- **한국어**: 형태소 분석기가 없어서 `simple` 구성(공백·구두점 분리, 소문자화, 어간 처리 없음)을 쓴다. 그래서 '스프링'은 tsvector로는 '스프링부트'와 맞지 않는다. 이 경우를 ILIKE 부분 일치가 맡는다. `pg_trgm` 확장과 `title`·`content`의 `gin_trgm_ops` 인덱스(V8, `posts_title_trgm_ix`·`posts_content_trgm_ix`)로 ILIKE를 인덱스로 처리한다. 트라이그램은 3글자 단위라서 2글자 검색어는 인덱스가 잘 걸러 주지 못한다.
- **구현**: Criteria에서 `search_tsv`를 쓰려고 `posts`를 한 번 더 매핑한 읽기 전용 엔티티 `PostSearchDocument`를 두었다. `Post`에 매핑하면 글을 읽을 때마다 tsvector를 함께 가져오기 때문이다. `@@`는 `FunctionContributor`(`SearchFunctions`)가 등록한 패턴 함수 `post_fts_match`로 그린다. 조건은 `p.id in (select d.id from posts d where …)`라는 비상관 서브쿼리이고, 페이지·count·`fetchDetails` 일괄 로딩 구조(N+1 없음)는 그대로다.
- **관련도 정렬 없음**: `sort=relevance`(ts_rank)는 만들지 않았다. ILIKE로만 맞은 글은 순위를 매길 수 없고, Spring Data `Sort`로 함수 정렬을 하려면 별도 쿼리가 필요하다. 제목 가중치(A)는 나중에 순위를 붙일 때 쓰려고 넣어 두었다.

### 이미지 첨부 (TASK-11)

업로드 본문은 앱을 거치지 않는다. 앱이 서명한 S3 presigned PUT URL로 브라우저가 직접 올리고, 앱은 확인만 한다. 모든 요청은 로그인이 필요하고, 세션이면 CSRF 토큰도 필요하다. 정지 계정과 제한 세션은 403이다.

1. `POST /api/attachments/presign` `{contentType, size, filename?}` → 200 `{attachmentId, key, uploadUrl, method:"PUT", headers, expiresAt}`
2. 클라이언트가 `uploadUrl`로 `PUT`하면서 `headers`(`content-type`, `x-amz-tagging: status=pending`)를 그대로 보낸다. 본문 길이는 `size`와 같아야 한다. `Content-Length`와 `Host`는 HTTP 클라이언트(브라우저)가 붙이므로 `headers`에 넣지 않았다. 셋 다 서명돼 있어서 값이 다르면 S3가 403(SignatureDoesNotMatch)으로 거부한다. URL은 5분간 유효하다.
3. `POST /api/attachments/{attachmentId}/confirm` → 200 `{id, url, contentType, size, status:"CONFIRMED"}`
4. 글 작성·수정에 `attachmentIds:[...]`를 넣으면 붙고, 상세 `attachments`에 나온다.

| 오류 | 언제 |
|---|---|
| 400 `attachment_type_not_allowed` | presign: `image/png`·`image/jpeg`·`image/webp`·`image/gif`가 아니다(대소문자·앞뒤 공백은 무시, `;charset` 같은 파라미터가 붙으면 거부) (B1) |
| 400 `attachment_too_large` | presign: `size` > 5MB(5242880바이트) (B1) |
| 400 `validation` | `contentType`·`size` 누락, `size` ≤ 0, `filename` 255자 초과, `attachmentIds` 10개 초과·null 원소 |
| 503 `uploads_unavailable` | 버킷이 설정되지 않았다(`UPLOADS_BUCKET` 비어 있음) |
| 404 `attachment_not_found` | confirm: 없는 첨부(또는 정리됨) |
| 403 `attachment_forbidden` | confirm: 남의 첨부(ADMIN 포함). 글 연결: 남이 올린 첨부, 또는 남의 글에 새 첨부를 붙이려 함 (B2) |
| 400 `attachment_not_uploaded` | confirm: 저장소에 객체가 없다(아직 PUT 전) |
| 400 `attachment_mismatch` | confirm: 저장된 객체의 크기·타입이 presign 때와 다르다. 객체는 지운다 |
| 400 `unknown_attachment` | 글 연결: 없는 첨부 id |
| 400 `attachment_not_confirmed` | 글 연결: confirm 전(PENDING) 첨부 |
| 400 `attachment_in_use` | 글 연결: 이미 다른 글에 붙은 첨부 |

- **키와 URL**: 키는 `uploads/{userId}/{uuid}.{ext}`다. 확장자는 타입에서 정하고(`jpg`·`png`·`webp`·`gif`), `filename`은 키에 쓰지 않는다(경로 조작 불가). 공개 `url`은 `UPLOADS_PUBLIC_BASE_PATH`에 키의 `uploads/` 뒤를 붙인 값이다. 운영은 `/uploads/7/<uuid>.png`처럼 같은 오리진 상대 경로이고, CloudFront `/uploads/*` 동작이 같은 키로 S3(OAC)를 읽는다(TASK-16).
- **confirm(AC#2)**: 본인만 할 수 있다. HeadObject로 객체가 있는지, 크기가 presign 때와 같고 5MB 이하인지, Content-Type이 같은지 다시 확인한다. 통과하면 객체 태그를 지우고(DeleteObjectTagging) `CONFIRMED`로 바꾼다. 이미 확정된 첨부를 다시 confirm하면 그대로 200이다. S3 호출 중에는 DB 트랜잭션을 잡지 않는다.
- **글 연결(B2)**: 새로 붙이는 첨부는 요청자 본인이 올렸고 글 작성자도 본인이어야 하며, `CONFIRMED`이고 다른 글에 붙어 있지 않아야 한다. 이미 그 글에 붙은 첨부는 그대로 둘 수 있다. ADMIN이 남의 글을 고칠 때도 기존 첨부를 유지하거나 뗄 수는 있지만 새로 붙일 수는 없다. 첨부 행을 `FOR UPDATE`로 잠근 뒤 연결하므로 같은 첨부가 두 글에 붙지 않는다. 실패하면 글 작성·수정 전체가 롤백된다. 연결을 뗀 첨부는 다시 붙일 수 있고, 글을 지워도 객체는 지우지 않는다.
- **미확정 정리(AC#4)**: presign이 `x-amz-tagging: status=pending`을 서명에 넣으므로 모든 업로드 객체에 태그가 붙는다. confirm이 이 태그를 지운다. S3 lifecycle(Terraform `infra/main/modules/uploads`)은 `uploads/` 아래 태그가 남은 객체를 1일 뒤 지운다. DB에서는 앱 스케줄러 `PendingAttachmentCleanup`이 1시간마다(`app.uploads.cleanup-interval`) 24시간(`app.uploads.pending-ttl`)이 지난 `PENDING` 행을 지우고, 객체도 best-effort로 지운다.
- **스키마**: V9 `attachments(owner_id, post_id NULL, s3_key UNIQUE, content_type, size, status PENDING|CONFIRMED, created_at, confirmed_at)`. CHECK 제약으로 타입·5MB와 "글에는 CONFIRMED만"을 DB에서도 막는다.
- **운영 권한**: 앱(EC2 role)에는 `uploads` 모듈 출력 `app_policy_json`을 붙인다. 내용은 `uploads/*`에 대한 Put/Get/Delete Object와 Put/Get/Delete ObjectTagging이다. presigned URL은 앱 자격 증명으로 서명하므로 PutObject와 PutObjectTagging 권한도 앱에 있어야 한다.

## 개발 워크플로

이 리포는 [backlog.md](https://github.com/MrLesk/Backlog.md) 태스크 단위로 작업한다.
`.claude-code-agile-hooks.json`에 테스트 명령(`make test`)과 test-first 대상 경로(`testFirst`)가 설정되어 있다.
