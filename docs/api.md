# 백엔드 API 레퍼런스

Spring Boot API의 상세 규칙이다. 모든 경로는 `/api` 아래에 있다(`server.servlet.context-path=/api`). 오류는 JSON `{"status","error","message"}`이다.
개요와 실행 방법은 [README](../README.md)에 있다. 이 문서는 TASK-18에서 README의 API 절을 옮긴 것이다.

- [오류 응답](#오류-응답-task-20) · [인증·인가 모델](#인증인가-모델-백엔드) · [인증 API (LOCAL)](#인증-api-local) · [휴대폰 OTP](#휴대폰-otp-로그인-task-7) · [소셜 로그인·계정 연결](#소셜-로그인-google--github--naver--kakao--cognito-과-계정-연결)
- [게시판](#게시판-api-task-8) · [댓글·좋아요·조회수](#댓글--좋아요--조회수-task-9) · [검색](#검색-task-10) · [이미지 첨부](#이미지-첨부-task-11) · [관리자](#관리자-api-task-12)
- 콘솔 설정: [oauth-setup.md](oauth-setup.md), [sms-setup.md](sms-setup.md)

## 오류 응답 (TASK-20)

모든 오류 응답은 `Content-Type: application/json`이고 본문은 `{"status", "error", "message"}`이다. 입력 검증 오류는 `fields`(필드·파라미터 이름 → 메시지)가 더 붙는다. `error`는 snake_case 코드이고, `message`는 화면에 그대로 보여도 되는 한국어 문장이다. 스택트레이스나 내부 예외 메시지는 응답에 넣지 않는다(서버 로그에만 남긴다).

| 상태 · `error` | 언제 |
|---|---|
| 400 `validation` | 본문 필드 검증 실패. 쿼리·경로 파라미터 오류(필수 파라미터 누락, 형식 오류 `?page=abc`·`/posts/abc`, 없는 enum 값, 범위 밖 `page`·`size`, `page × size`(OFFSET)가 10,000을 넘는 깊은 페이지(TASK-23#5, 글·댓글·관리자 목록 모두), 지원하지 않는 `?token=`)도 모두 이 코드이고 `fields`의 키는 파라미터 이름이다. enum 파라미터(예: `status`)는 앞뒤 공백을 무시하고 대소문자를 가리지 않는다. 본문 문자열·쿼리 파라미터에 제어 문자(U+0000–U+001F 중 탭·LF·CR 제외, U+007F–U+009F)가 있을 때도 같다. DB가 데이터 오류(SQLState 22xxx)로 거부한 값도 400 `validation` |
| 400 `validation` (본문) | 요청 본문의 알 수 없는 필드(오타 PATCH가 조용히 무시되지 않는다), 필드 타입 오류. `fields`의 키는 본문 경로다(예: `titel`, `sortOrder`, `attachmentIds[1]`) |
| 400 `bad_request` | 본문이 JSON 문법에 맞지 않거나 비었다(특정 필드 탓이 아님). 요청 조합이 맞지 않는다(예: Bearer 요청의 세션 로그인, `link=true`와 `token` 동시 사용) |
| 409 `conflict` | 앞단 검사를 지나 DB 유니크(23505)·FK(23503) 제약에 걸렸고 엔드포인트 고유 코드(예: `email_taken`, `slug_taken`)가 없을 때. NOT NULL(23502)·CHECK(23514) 위반은 앞단 검증이 놓친 서버 버그로 보고 500 `internal_error`. 글을 읽은 뒤 다른 요청이 같은 글을 먼저 바꿔 커밋했을 때(낙관적 잠금, 아래 게시판 동시 수정)도 409 `conflict` |
| 401 `unauthorized` · 403 `forbidden`·`csrf`·`suspended`·`step_up_required` | 보안 체인(아래 인증·인가 모델) |
| 404 `not_found` | 없는 경로(인증된 요청. 익명은 먼저 401) |
| 405 `method_not_allowed` | 경로는 있지만 메서드가 다르다. `Allow` 헤더가 붙는다 |
| 406 `not_acceptable` | `Accept`가 JSON을 받지 않는다(본문은 그래도 JSON) |
| 413 `payload_too_large` | 요청 본문이 64KB(`app.request.max-body-size`)를 넘었다(TASK-23#2). `Content-Length`가 넘으면 본문을 읽지 않고, 길이를 모르는(chunked) 본문은 읽다가 넘는 순간 끊는다. form 본문(`application/x-www-form-urlencoded`, chunked 포함)도 같은 상한이다(컨테이너 `server.tomcat.max-http-form-post-size=64KB`). multipart 는 받지 않는다(파싱하지 않음 — 415). 가장 큰 정상 글(한글 제목 100자 + 본문 20000자 ≈ 60KB)은 들어간다. 첨부 이미지는 S3로 바로 올리므로(아래 TASK-11) 해당하지 않는다. 본문 문자열을 `\uXXXX`로 이스케이프하면 글자당 6바이트라 한도에 먼저 걸릴 수 있다(브라우저 `JSON.stringify`는 이스케이프하지 않는다) |
| 415 `unsupported_media_type` | 본문이 `application/json`이 아니다 |
| 409 `lock_timeout` | 다른 요청이 같은 행을 잠그고 있어 `lock_timeout`(기본 5s)을 넘겼거나 교착(40P01)으로 취소됐다(TASK-23#1). 잠시 뒤 다시 시도하면 된다 |
| 429 `rate_limited` | 요청 한도 초과(아래 레이트리밋). `Retry-After` 헤더(초)가 붙는다 |
| 500 `internal_error` | 처리되지 않은 서버 오류 |
| 503 `db_unavailable` | DB 에 닿지 못했다: 커넥션 풀 대기 초과(기본 5s), 응답 없는 DB 의 소켓 타임아웃(기본 10s), 연결 오류(SQLState 08xxx·57P01~03·53xxx). 세션 조회처럼 필터 단계에서 난 경우도 같다. DB 장애 중에도 응답은 30초 안에 이 JSON 으로 끝난다(TASK-23 B2) |
| 503 `db_timeout` | 쿼리 하나가 `statement_timeout`(기본 5s)을 넘겨 DB 가 취소했거나(57014), 트랜잭션 하나(문장 여러 개의 합)가 `transaction_timeout`(기본 10s)을 넘겨 DB 가 세션을 끝냈다(FATAL 25P04 — 뒤이은 롤백이 끊긴 커넥션에서 실패해도 이 코드다. 그 커넥션은 풀에서 버린다). DB 는 살아 있지만 과부하일 때다. 잠시 뒤 다시 시도. 상한은 트랜잭션마다다 — 한 요청이 트랜잭션 여러 개나 autocommit 문장 여러 개를 쓰면 이론상 합이 더 길 수 있다 |
| 그 밖의 상태 | 필터·컨테이너에서 난 오류(`/api/error`)도 같은 표를 따른다: 409 `conflict`, 413 `payload_too_large`, 429 `rate_limited`, 503 `service_unavailable`. 표에 없는 상태는 `http_<상태>`(예: 502 `http_502`) |

### 레이트리밋 (TASK-22)

한도는 모두 DB(`rate_limit_events`) 기준 슬라이딩 창이라 인스턴스가 여러 개여도 같다. 넘으면 429 `rate_limited` + `Retry-After`(초: 창 안의 이벤트가 하나 빠져 한도 아래로 내려갈 때까지). 키(이메일·IP·사용자 id)는 SHA-256 해시로만 저장한다. 설정은 `app.rate-limit.*`(README 설정 표). ADMIN도 예외가 아니다(계정 탈취·스크립트 실수도 같은 한도로 막는다).

| 대상 | 키 | 기본 한도 | 세는 것 |
|---|---|---|---|
| `POST /api/auth/login` | (계정(정규화 이메일), 클라이언트 IP) | 15분 창 안 실패 5회 | 실패(`invalid_credentials`)만. 한도에 이르면 비밀번호를 검사하지 않고 429 — 계정이 있든 없든 같은 응답(B2). 성공은 세지 않지만 이미 센 실패를 지우지도 않는다. 키에 IP를 묶어 다른 IP의 공격자가 피해자 계정을 잠그지 못한다(그 IP만 막힌다). 여러 IP로 나눈 추측은 아래 IP 한도가 막는다 |
| `POST /api/auth/login` | 클라이언트 IP | 1시간 30회 | 실패만 |
| `POST /api/auth/signup` | 클라이언트 IP | 1시간 10회 | 시도(201·409 모두) |
| `POST /api/auth/step-up/local` | 사용자 | 15분 창 안 실패 5회 | 실패만. 제한 세션·토큰을 바꿔도 같은 사용자다(B1) |
| `POST /api/posts` | 사용자 | 1분 3회, 1시간 30회 | 성공한 작성(검증 오류 등으로 실패한 요청은 세지 않는다) |
| `POST /api/posts/{id}/comments` | 사용자 | 1분 10회 | 성공한 작성 |
| `POST /api/attachments/presign` | 사용자 | 1시간 60회 | 성공한 presign. 미확정(PENDING) 첨부가 20개면 409 `too_many_pending_attachments`(시간이 아니라 confirm·정리로 풀리므로 `Retry-After` 없음) |
| `POST /api/auth/phone/otp` | 번호·IP | 아래 휴대폰 OTP 절 | 발송(`otp_challenges`) |

- 고정 시간 잠금이 아니라 슬라이딩 창이다. 예: 로그인 "15분 창 안 실패 5회"에서 t0에 1번·t0+14:50에 4번 틀리면 약 10초(`Retry-After`) 막히고, t0 실패가 창 밖으로 나가면 한 번 더 시도할 수 있으며, 그것도 틀리면 다음으로 오래된 실패(t0+14:50)가 나갈 때까지 다시 막힌다.
- "세고 → 넣기"는 (한도, 키)별 트랜잭션 advisory lock으로 직렬화하므로 동시 요청으로 넘길 수 없다. 이 잠금 대기에도 `lock_timeout`(기본 5s)이 걸린다 — 같은 키의 요청이 잠금을 그보다 오래 쥐고 있으면(DB 과부하 등) 레이트리밋 대상 요청은 429 대신 409 `lock_timeout`을 받을 수 있다(TASK-23#1). 잠시 뒤 다시 시도하면 된다. 실패만 세는 로그인·step-up은 비밀번호 검사 전에 자리를 먼저 잡아 두고(바로 커밋) 성공하면 지운다 — 검사 중인 요청까지 센다.
- 실패만 세는 쪽은 처리 중인 시도도 세므로, 한 NAT 뒤에서 로그인이 동시에 많이 몰리면(맞는 비밀번호여도) IP 한도에 걸려 잠깐 429를 받을 수 있다. 성공한 시도는 끝나는 대로 지워져 곧 풀린다.
- 시각은 DB가 아니라 앱 서버의 시계(`Clock`)로 기록·비교한다. 인스턴스끼리 시계가 어긋나면 그 차이만큼 덜 셀 수 있다(NTP 동기화 전제 — 초 단위 오차는 분·시간 창에서 무시할 수 있다).
- 클라이언트 IP는 휴대폰 OTP와 같은 규칙(`CLIENT_IP_HEADER`)이다.
- 오래된 행 정리(가장 긴 창 1시간보다 오래된 행)는 TASK-27 정리 잡에서 한다.

엔드포인트별 고유 코드(예: `email_taken`, `post_not_found`)는 각 절에 있다. 다른 예외에 감싸여 올라온 고유 코드도 500으로 바뀌지 않고 그 코드로 나간다. 응답에 되돌려 주는 파라미터·필드 이름은 100자까지만 담는다.

## 인증·인가 모델 (백엔드)

<!--dot:auth-model-->
<p align="center"><img src="diagrams/auth-model.svg" alt="인증 모델 — users 1:N user_identities(provider, subject), local_credentials·refresh_tokens, 요청은 Bearer JWT 와 SESSION 쿠키(+CSRF) 중 하나로 인증된 뒤 공통 인가 필터를 지난다"></p>
<!--/dot:auth-model-->

- **계정**: `users` 1 : N `user_identities(provider, subject)`. 비밀번호는 `local_credentials`에만 있다(decision-4).
- **하나의 보안 체인**(decision-5): `Authorization: Bearer <JWT>`가 있으면 JWT로 인증하고 세션은 읽지도 쓰지도 않는다.
  없으면 `SESSION` 쿠키(Spring Session JDBC, HttpOnly·SameSite=Lax)로 인증한다. 토큰이 틀리면 세션이 있어도 401.
- **만료된 Bearer**(TASK-24#1): `POST /api/auth/token/refresh`·`POST /api/auth/logout`·공개 GET(아래 표의 익명 읽기와 `/auth/csrf`·헬스체크)은
  access 토큰이 *만료만* 됐으면(서명·발급자·형식은 맞음) 헤더를 무시하고 익명으로 처리한다. 그래서 만료된 헤더를 붙인 채로도 refresh 를 받고,
  로그아웃하면 그 토큰 주인의 refresh family 가 폐기된다(남의 refresh 토큰은 폐기하지 않는다). 서명이 틀린 토큰과 보호된 API 의 만료 토큰은 그대로 401.
- **깨진 세션**(TASK-24#5): 역직렬화할 수 없는 세션(배포 뒤 클래스 변경, 깨진 데이터)은 세션 없음·만료와 같다. 그 세션을 지우고
  `SESSION` 쿠키를 만료시킨 뒤 익명으로 계속한다 — 보호된 API 는 401 `unauthorized`, 공개 GET 은 200. 500 이 아니다. 깨진 세션 전용 오류
  코드는 따로 두지 않는다(클라이언트는 기존 401 `unauthorized` 를 로그아웃 상태로 다루면 된다).
- **CSRF**: 세션 요청의 POST/PUT/PATCH/DELETE는 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 보내야 한다.
  SPA는 처음에 `GET /api/auth/csrf`로 쿠키를 받는다. Bearer 요청은 CSRF 면제.
- **오류**: 미인증 401, 권한 없음 403, 모두 JSON(`{"status","error","message"}`). 로그인 페이지 리다이렉트는 없다.
- **인가**: 기본은 인증 필요. 역할 `USER`/`ADMIN`은 `@PreAuthorize`, 수정·삭제는 `@ownership.canModify(authentication, ownerId)`
  (본인 또는 ADMIN). `SUSPENDED` 계정은 읽기만 되고 모든 쓰기가 403 `suspended`(로그아웃·로그인 계열 제외, 보안 체인에서 공통 처리).
- **역할·상태는 요청마다 DB 값**(TASK-24#2): 세션·토큰에 실린 role/status 는 발급 시점 값이라, 인증된 요청마다 DB 의 최신 role·status 로
  판정한다(PK 조회 1회). 강등·승격·정지는 같은 세션·토큰의 다음 요청부터 반영되고, 계정이 지워졌으면 익명(보호된 API 401)이다.
- **정지 계정의 로그인 계열**(TASK-24#4): 정지 계정의 세션·토큰이 남은 클라이언트도 로그인·가입·refresh·OTP 발송·검증은 공통 필터에 막히지
  않고 각 엔드포인트의 계약대로 응답한다(다른 계정이면 정상 로그인, 정지 계정 자신이면 비밀번호가 맞을 때 403 `suspended`
  "정지된 계정은 로그인할 수 없습니다"). 현재 계정을 바꾸는 OTP 연결(`?link=true`)은 그대로 403 `suspended`. 연결 모드 판정은 컨트롤러와
  같은 디코딩된 `link` 값으로 한다: 필터 예외는 `link` 가 없거나 모든 값이 (앞뒤 공백을 뺀) `false`(대소문자 무시)일 때뿐이고, 그 밖의 값
  (`true`·`1`·`on`·`yes`, `%74rue`·`+true` 같은 인코딩 변형, 빈 값)은 연결 모드로 보고 정지 계정이면 403 `suspended` 다. 연결 처리 자체도
  ACTIVE 가 아닌 계정을 403 `suspended` 로 거절한다(심층 방어).
- **제한 세션**(재활용 번호 보호, 아래 휴대폰 로그인): 읽기만 되고 쓰기는 403 `step_up_required`(로그아웃·step-up·로그인 계열 제외).
- `/api/actuator/health`는 공개, 세부 정보(DB 등)는 ADMIN에게만.

### 인증 API (LOCAL)

| 요청 | 설명 |
|---|---|
| `POST /api/auth/signup` `{email, password, nickname}` | 201 + 사용자 요약. 자동 로그인 없음. 비밀번호 8자 이상·72바이트 이하·문자+숫자, 닉네임 2~20자. 같은 이메일(대소문자 무시) 중복은 409. CSRF 필요 |
| `POST /api/auth/login` `{email, password}` | 세션 로그인: 세션 ID 교체 + `SESSION` 쿠키 + 새 `XSRF-TOKEN` 쿠키, 200 + 사용자 요약. CSRF 필요 |
| `POST /api/auth/login?token=jwt` | 세션 없이 `{accessToken, refreshToken, tokenType:"Bearer", expiresIn}`. CSRF 면제(쿠키를 만들지 않는 비브라우저 흐름) |
| `POST /api/auth/token/refresh` `{refreshToken}` | 로테이션: 새 access+refresh. 이미 쓴 refresh를 다시 쓰면 그 로그인(family)의 토큰을 모두 폐기하고 401. 정지 계정이면 그 family 를 폐기하고 403 `suspended`(정지로 이미 폐기된 토큰이면 401 `invalid_refresh_token`). Authorization 헤더는 필요 없다(만료된 access 토큰이 붙어 있으면 무시). CSRF 면제 |
| `POST /api/auth/logout` | 세션: 세션 삭제 + 쿠키 만료(CSRF 필요). Bearer: 본문 `{refreshToken}`이 그 사용자 것이면 그 family 폐기(access 토큰이 만료됐어도). 항상 204, 정지 계정도 가능 |
| `GET /api/auth/me` | 현재 사용자 `{id, nickname, email, role, status, providers, stepUpRequired, stepUpProviders}` (DB 최신 값). 익명은 401. `stepUpRequired`면 이 세션·토큰은 제한 상태이고 `stepUpProviders`는 다시 인증할 수 있는 수단 |

- 로그인 실패는 이유와 상관없이 같은 401(`invalid_credentials`)이다. 정지 계정은 비밀번호가 맞을 때만 403(`suspended`).
- 로그인과 정지가 동시에 일어나도(TASK-24#3) 살아 있는 자격이 남지 않는다. JWT 발급(LOCAL·OTP·step-up)은 refresh 를 저장하는 트랜잭션에서
  사용자 행을 `FOR SHARE` 로 잠그고 status 를 다시 봐서, 정지가 먼저 커밋됐으면 403 `suspended`(refresh 를 남기지 않음), 로그인이 먼저면 정지가
  새 refresh 까지 폐기한다. refresh 로테이션도 같다: 토큰 행보다 먼저 사용자 행을 `FOR SHARE` 로 잠그고 status 를 다시 봐서, 정지가 먼저면
  새 토큰을 만들지 않고(403 `suspended`, 정지가 이미 폐기한 토큰이면 401 `invalid_refresh_token`), 로테이션이 먼저면 정지가 새 refresh 까지
  폐기한다. 세션 로그인은 세션이 저장된 뒤 status 를 다시 봐서 정지됐으면 그 사용자 세션을 지운다 — 이 경쟁에서 로그인 응답은 200 일 수
  있지만 그 세션은 이미 지워져 다음 요청부터 익명(보호된 API 401)이다. 재확인 조회가 DB 오류로 실패해도 그 사용자의 세션 삭제를 시도한다.
- 레이트리밋(TASK-22): 로그인 실패는 (계정, IP)별 15분 창 안 5회(슬라이딩 창)·IP별 1시간 30회, 가입은 IP별 1시간 10회. 넘으면 429 `rate_limited` + `Retry-After`(위 레이트리밋 표).
- refresh 토큰은 256비트 랜덤 값이고 DB에는 SHA-256 해시만 둔다. access 토큰은 상태가 없어 로그아웃 뒤에도 만료(15분)까지 유효하다.

### 휴대폰 OTP 로그인 (TASK-7)

번호 + 인증번호로 하는 비밀번호 없는 로그인·가입이다(provider=`PHONE`, subject=E.164 번호, decision-7).

| 요청 | 설명 |
|---|---|
| `POST /api/auth/phone/otp` `{phone}` | 202 `{challengeId, expiresIn:180}` + 6자리 인증번호 SMS. 계정이 있든 없든 같은 응답. 공개, CSRF 필요(Bearer는 면제) |
| `POST /api/auth/phone/verify` `{challengeId, code}` | 맞으면 (PHONE, 번호) 계정으로 로그인, 없으면 새 계정(+ `users.phone`). 세션 로그인(세션 ID 교체 + 쿠키), 200 + 사용자 요약. CSRF 필요 |
| `POST /api/auth/phone/verify?token=jwt` | 세션 없이 access+refresh. CSRF 면제(`login?token=jwt`와 같은 이유) |
| `POST /api/auth/phone/verify?link=true` | (로그인 필요, 세션·Bearer) 현재 계정에 PHONE 연결 + 비어 있으면 `users.phone`, 200 + 사용자 요약. 다른 계정의 번호면 409 `identity_in_use`. 세션이면 CSRF 필요(`token`과 같이 쓸 수 없다) |
| `POST /api/auth/step-up/local` `{password[, refreshToken]}` | (로그인 필요) 제한 세션·토큰을 비밀번호로 풀기. 세션: 세션 ID를 바꾼 제한 없는 세션. Bearer: 제한 없는 새 토큰(본문 refreshToken의 family 폐기). 틀리면 401 `invalid_credentials`, LOCAL이 없으면 400 `step_up_unavailable`. 사용자별 15분 창 안 실패 5회(슬라이딩 창)에 이르면 429 `rate_limited`(B1, 비밀번호 추측 차단) |

- 번호는 libphonenumber로 E.164로 정규화한다(`+` 없으면 `SMS_DEFAULT_REGION`=KR). 형식이 틀리면 400 `invalid_phone`.
- **발송 대상**(TASK-22#4, SMS pumping 방지): 허용 지역(`SMS_ALLOWED_REGIONS`, 기본 `KR`, 쉼표로 여럿)의 휴대폰(libphonenumber 유형
  MOBILE·FIXED_LINE_OR_MOBILE)만. 다른 나라 번호, 유선·인터넷전화(070)·대표번호·수신자부담 번호는 400 `phone_not_allowed`. 발송 한도보다
  먼저 검사하므로 거절된 번호는 챌린지·SMS를 만들지 않고 한도도 소모하지 않는다(B3).
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
  IAM에 `sms-voice:SendTextMessage`가 필요하다. 샌드박스 해제와 한국행 발신 등록(발신 번호·sender ID)은 콘솔 작업이다 — [sms-setup.md](sms-setup.md).

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

OAuth 로그인 흐름(시퀀스라 DOT 대신 mermaid로 그렸다):

```mermaid
sequenceDiagram
    autonumber
    participant B as 브라우저
    participant CF as CloudFront /api/*
    participant API as Spring Boot
    participant IdP as 제공자 (Google 등)
    B->>CF: GET /api/oauth2/authorization/{id}
    CF->>API: (쿠키·헤더 그대로)
    API->>API: state·nonce 를 세션(JDBC)에 저장
    API-->>B: 302 → IdP 인가 화면 (redirect_uri = PUBLIC_BASE_URL/api/login/oauth2/code/{id})
    B->>IdP: 로그인·동의
    IdP-->>B: 302 → /api/login/oauth2/code/{id}?code&state
    B->>CF: GET 콜백 (SESSION 쿠키)
    CF->>API: (그대로 전달)
    API->>IdP: code → token (+ userinfo / id_token 검증)
    API->>API: (provider, subject) 로 계정 찾기·만들기, 세션 ID 교체
    API-->>B: 302 /auth/callback/?status=ok + SESSION · XSRF-TOKEN 쿠키
    B->>CF: GET /api/auth/me
```

**제공자 콘솔 설정**(단계별 절차와 확인 체크리스트는 [oauth-setup.md](oauth-setup.md)) — Authorized redirect URI(callback URL)에 다음을 그대로 넣는다(`PUBLIC_BASE_URL`이 바뀌면 모든 콘솔에서 바꾼다):

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
| `GET /api/posts?category=<slug>&tag=<name>&q=<검색어>&page=0&size=20&sort=latest` | `{items:[{id, title, excerpt, author:{id, nickname}, category:{id, slug, name}\|null, tags, createdAt, updatedAt, deleted, hidden, likeCount, viewCount}], page, size, totalElements, totalPages}`. 오프셋 페이징. `size` 1~50(기본 20), `page` ≥ 0, `page × size` ≤ 10,000(넘으면 400 `validation` + `fields.page`, TASK-23#5 — 더 깊은 페이지는 검색·필터로 좁힌다). `sort`는 `latest`(기본, `created_at`·`id` 내림차순)와 `popular`(TASK-9: `like_count` 내림차순, 같으면 최신순)만 받고 대소문자는 가리지 않는다. 범위를 벗어나거나 형식이 틀리면 400. `tag`는 태그와 같은 규칙으로 정규화한다. `excerpt`는 공백을 접은 본문 앞 200자이고, 잘렸으면 끝에 `…`를 붙인다 |
| `GET /api/posts/{id}` | `{id, title, content, author, category, tags, createdAt, updatedAt, editable, deleted, hidden, likeCount, likedByMe, viewCount, attachments}`. `attachments`는 붙은 이미지 `[{id, url, contentType, size, status}]`(올린 순, 아래 TASK-11). `editable`은 보는 사람이 작성자나 ADMIN이고 글이 삭제되지 않았을 때 true. `likedByMe`는 보는 사람이 좋아요를 눌렀는지(익명은 false). 조회수를 올린다(아래 TASK-9). 없으면 404 `post_not_found` |
| `POST /api/posts` `{title, content, categorySlug?, tags?, attachmentIds?}` | 201 + `Location` + 상세. `attachmentIds`(최대 10)는 본인이 올려 확정한 첨부(아래 TASK-11). 제목은 1~100자, 본문은 1~20000자이고 둘 다 공백만으로는 쓸 수 없다(어기면 400 `validation`). 없는 카테고리는 400 `unknown_category`. 사용자별 1분 3개·1시간 30개를 넘으면 429 `rate_limited` + `Retry-After`(TASK-22#2, ADMIN 포함) |
| `PATCH /api/posts/{id}` | 보낸 필드만 바꾼다(빠진 필드나 null은 그대로 둔다). `categorySlug:""`는 카테고리를 빼고, `tags:[]`는 태그를 모두 지운다. `attachmentIds`는 첨부 목록을 통째로 바꾸고(빠진 첨부는 연결 해제), `[]`는 모두 뗀다. 200 + 상세 |
| `DELETE /api/posts/{id}` | soft delete(`deleted_at`). 204 |
| `GET /api/categories` | `[{id, slug, name, sortOrder, adminOnly}]`(sort_order 순). 기본 카테고리는 `notice`(공지, ADMIN 전용)와 `free`(자유게시판) |
| `GET /api/tags?q=<접두어>&limit=20` | `[{name, postCount}]`. 보이는 글에 붙은 태그만, 글 수 내림차순(같으면 이름순)이다. `q`는 소문자와 공백을 정규화한 접두어이고 `%`·`_`도 글자 그대로 비교한다. `limit`은 1~100 |

- **수정·삭제 권한(B1)**: 작성자와 ADMIN만 할 수 있다. `@PreAuthorize("@ownership.canModify(authentication, @postService.ownerOf(#id, authentication))")`로 검사하며, 글이 없거나 이미 삭제됐으면 404를 먼저 낸다. 숨긴 글은 ADMIN이 아니면 없는 글처럼 404다. 그다음 남의 글이면 403 `forbidden`이다. 삭제된 글은 ADMIN도 수정할 수 없다(복구는 TASK-12).
- **삭제·숨김 글(B2)**: ADMIN이 아니면 목록에 나오지 않고 상세는 404다. ADMIN은 상세에서 `deleted`·`hidden` 표시와 함께 볼 수 있고, 목록은 `?includeHidden=true`를 붙였을 때만 포함한다(ADMIN이 아니면 이 파라미터는 무시한다).
- **태그(B3)**: 이름은 NFC로 정규화한 뒤 앞뒤 공백을 지우고, 안쪽 공백을 한 칸으로 줄이고, 소문자로 바꾼다. 30자를 넘거나 비면 400 `invalid_tag`다. 한 글 안의 중복은 하나로 합치며, 합친 뒤 5개를 넘으면 400 `too_many_tags`다(입력 배열은 20개까지 받는다). 태그는 `INSERT ... ON CONFLICT (name) DO NOTHING`으로 넣은 뒤 다시 읽어서 재사용하므로, 같은 새 태그를 동시에 써도 행은 하나만 생긴다.
- **동시 수정(TASK-23#3, B1)**: 글에는 낙관적 잠금 버전(`posts.version`)이 있다. 작성자 수정·삭제와 관리자 숨김·숨김 해제·복구가 동시에 겹치면 늦게 커밋하는 쪽이 409 `conflict`이고 아무것도 바뀌지 않는다. 그래서 먼저 커밋된 숨김·삭제가 늦은 수정에 덮여 되살아나지 않는다. 클라이언트는 버전을 보내지 않는다(`If-Match`·`version` 필드 없음, 기존 요청 그대로) — 서버 트랜잭션끼리 겹친 경쟁만 잡고, 409를 받으면 다시 읽고 시도한다. 오래 열어 둔 편집 화면의 덮어쓰기(읽은 뒤 몇 분 지나 저장)는 잡지 않는다(후속). 좋아요·조회수 카운터는 SQL로 ±1 하므로 버전을 올리지 않는다(좋아요가 수정을 409로 만들지 않는다).
- **ADMIN 전용 카테고리**: `categories.admin_only=true`인 카테고리(공지)에 글을 쓰거나 글을 옮기는 것은 ADMIN만 할 수 있다. USER는 403 `category_admin_only`를 받는다.

### 댓글 · 좋아요 · 조회수 (TASK-9)

댓글 목록은 공개다. 댓글 작성·삭제와 좋아요·취소는 로그인이 필요하고, 세션이면 CSRF 토큰도 필요하다. 정지 계정과 제한 세션은 403(`suspended`·`step_up_required`)이다.

| 요청 | 설명 |
|---|---|
| `GET /api/posts/{id}/comments?page=0&size=20` | `{items:[{id, postId, content, author:{id, nickname}, createdAt, updatedAt, deleted, editable}], page, size, totalElements, totalPages}`. 오래된 순(`created_at`·`id` 오름차순). `size` 1~100(기본 20). 글이 없거나 보는 사람에게 보이지 않으면(삭제·숨김, ADMIN 제외) 404 `post_not_found` |
| `POST /api/posts/{id}/comments` `{content}` | 201 + `Location: /api/comments/{id}` + 댓글. 본문 1~2000자, 공백만은 400 `validation`. 삭제·숨김 글에는 ADMIN도 쓸 수 없다(404 `post_not_found`, B3). 사용자별 1분 10개를 넘으면 429 `rate_limited`(TASK-22#2) |
| `DELETE /api/comments/{id}` | 작성자 또는 ADMIN. soft delete, 204. 없거나 이미 삭제됐거나 글이 보이지 않으면 404 `comment_not_found`, 남의 댓글이면 403 `forbidden` |
| `POST /api/posts/{id}/like` | 멱등. 200 `{liked:true, likeCount}`. 이미 눌렀어도 200이고 수는 그대로다 |
| `DELETE /api/posts/{id}/like` | 멱등. 200 `{liked:false, likeCount}`. 삭제·숨김·없는 글이면 좋아요·취소 모두 404 `post_not_found` |

- **삭제된 댓글 표시**: 목록에서 빼지 않고 자리에 남긴다. `deleted:true`이고 `content`·`author`는 null, `editable`은 false다. 프론트는 "삭제된 댓글입니다"로 보여 준다. 그래서 페이지가 밀리지 않고 대화 순서가 유지된다. 댓글 수정 API는 없다.
- **좋아요와 숨김·삭제(TASK-23#4)**: 좋아요·취소는 글이 보이는지 확인할 때 글 행을 잠근다(`FOR NO KEY UPDATE`). 확인 뒤의 숨김·삭제는 좋아요가 끝날 때까지 기다리고, 먼저 커밋된 숨김·삭제는 좋아요가 보고 404 `post_not_found`다. 글 행이 없어도 404다. 댓글 작성처럼 `FOR SHARE`를 쓰지 않는 이유: 좋아요는 이어서 같은 행의 `like_count`를 UPDATE 하므로, 공유 잠금을 쥔 동시 좋아요끼리 교착(40P01)이 난다. 같은 글의 좋아요는 이 잠금에서 줄을 선다(어차피 카운터 UPDATE에서 줄 선다).
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
| 409 `too_many_pending_attachments` | presign: 미확정(PENDING) 첨부가 이미 20개(`app.rate-limit.max-pending-attachments`). confirm하거나 정리(24시간)되면 풀린다 (TASK-22#3) |
| 429 `rate_limited` | presign: 사용자별 1시간 60회 초과. `Retry-After`(초) (TASK-22#3) |
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

### 관리자 API (TASK-12)

`/api/admin/**`는 보안 체인에서 `ADMIN`만 통과한다(익명 401, `USER`·정지 계정 403). 관리 쓰기도 다른 쓰기와 같이 세션이면 CSRF가 필요하고, 정지·제한(step-up) 필터를 거친다. 역할은 요청마다 DB 값으로 판정한다(TASK-24#2) — DB 에서 강등하면 그 사람의 세션·JWT 로 하는 다음 관리자 API 요청부터 403 `forbidden`이다. 역할 변경(승격·강등) API는 없다(`ADMIN_EMAIL` 부트스트랩만).

| 요청 | 응답 |
|---|---|
| `GET /api/admin/users?q&status&page&size` | `{items:[{id, nickname, email, phone, role, status, providers[], createdAt}], page, size, totalElements, totalPages}` 최근 가입순. `q`는 닉네임·이메일 부분 일치(대소문자 무시) 또는 전화번호 부분 일치(숫자만 비교, `010-1234-5678`처럼 앞자리 0인 국내 표기도 찾는다). `status`=`ACTIVE`\|`SUSPENDED`(대소문자 무시), `size` 1~100(기본 20) |
| `POST /api/admin/users/{id}/suspend` `{reason?}` | 200 사용자 한 줄(`status:"SUSPENDED"`). 멱등 |
| `POST /api/admin/users/{id}/unsuspend` `{reason?}` | 200 사용자 한 줄(`status:"ACTIVE"`). 멱등. 끊긴 세션·토큰은 되살리지 않는다(다시 로그인) |
| `POST /api/admin/posts/{id}/hide` · `/unhide` · `/restore` `{reason?}` | 200 `{id, hidden, deleted, hiddenAt, deletedAt}`. 멱등(다시 숨겨도 처음 숨긴 시각 유지). `restore`는 `deleted_at`만 지우고 숨김은 그대로 둔다. 작성자 수정·삭제와 겹치면 늦은 쪽이 409 `conflict`(위 게시판 동시 수정, TASK-23#3) |
| `POST /api/admin/categories` `{slug, name, sortOrder?, adminOnly?}` | 201 `{id, slug, name, sortOrder, adminOnly}`. `sortOrder` 생략 시 맨 뒤(최대 + 1), `adminOnly` 생략 시 false |
| `PATCH /api/admin/categories/{id}` `{slug?, name?, sortOrder?, adminOnly?}` | 200 같은 모양. 보낸 필드만 바꾼다 |
| `DELETE /api/admin/categories/{id}` | 204. 그 카테고리의 글은 남고 카테고리 없음이 된다(FK `ON DELETE SET NULL`) |
| `GET /api/admin/actions?page&size` | 감사 로그 최근순 `{items:[{id, adminId, adminNickname, action, targetType, targetId, reason, createdAt}], ...}` |

| 오류 | 언제 |
|---|---|
| 409 `cannot_suspend_self` | 자기 자신을 정지하려 함 (B2) |
| 409 `cannot_suspend_admin` | 다른 ADMIN을 정지하려 함. 관리자끼리 서로 잠그는 것을 막는다. ADMIN 정지가 꼭 필요하면 DB에서 역할부터 바꾼다 |
| 404 `user_not_found` · `post_not_found` · `category_not_found` | 없는 대상 |
| 409 `slug_taken` | 카테고리 slug 중복(동시 생성의 유니크 위반 `categories_slug_uk`도 같은 409). 다른 DB 오류는 slug_taken으로 바꾸지 않고 공통 매핑(409 `conflict` · 400 `validation` · 500 `internal_error`)을 따른다 |
| 400 `validation` | slug가 `^[a-z0-9-]{2,30}$`가 아님, name이 비었거나 50자 초과, `reason` 500자 초과, 잘못된 `page`·`size`·`status` |

- **정지는 즉시 효과**: 한 트랜잭션에서 `status=SUSPENDED`, 그 사용자의 refresh 토큰 전부(모든 family) 폐기, 감사 로그를 커밋하고, 커밋 뒤 Spring Session JDBC에서 그 사용자의 세션을 모두 지운다. 세션은 `SPRING_SESSION.PRINCIPAL_NAME` 인덱스로 찾는데, Spring Session이 세션의 SecurityContext에서 `Authentication#getName()`(= 사용자 id)을 뽑아 채우므로 세션·소셜·OTP 로그인 세션이 모두 사용자 id로 색인된다. 결과: 기존 세션은 다음 요청부터 익명(보호된 API는 401), refresh는 401, 이미 받은 access JWT는 만료(15분)까지 읽기만 되고 쓰기는 403, 로그인은 403 `suspended`.
- **감사 로그**: V10 `admin_actions(id, admin_id, action, target_type, target_id, reason, created_at)`. 모든 관리 쓰기(`USER_SUSPEND`·`USER_UNSUSPEND`·`POST_HIDE`·`POST_UNHIDE`·`POST_RESTORE`·`CATEGORY_CREATE`·`CATEGORY_UPDATE`·`CATEGORY_DELETE`)를 그 작업과 같은 트랜잭션에서 한 행씩 남긴다. 상태가 이미 같아 바뀐 게 없어도 호출마다 기록한다. 빈 사유는 null, 카테고리 삭제는 사유에 `slug=...`를 남긴다. `target_id`는 대상이 지워져도 남도록 FK가 없다.
