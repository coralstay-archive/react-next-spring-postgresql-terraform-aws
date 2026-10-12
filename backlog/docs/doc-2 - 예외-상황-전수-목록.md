---
id: doc-2
title: 예외 상황 전수 목록 (2026-10-12 감사)
type: specification
---

# 예외 상황 전수 목록

2026-10-12에 읽기 전용 감사 에이전트 3개(백엔드, 프론트, 인프라·운영)로 코드베이스를 훑어 만든 목록이다.
- 판정: **확인**은 코드를 읽고 확정한 것, **추정**은 프레임워크·SDK·AWS 기본 동작에 근거한 판단이다.
- 심각도: H(High), M(Medium), L(Low).
- 각 항목은 아래 "draft 매핑"의 태스크 AC에서 ID로 참조한다.

## 백엔드 (BE)

| ID | 문제 | 위치 | 심각도 | 판정 |
|---|---|---|---|---|
| BE-1 | 공통 예외 처리기가 없어 405·415·406·404·500이 Boot 기본 모양(`{timestamp,status,error:"Method Not Allowed",path}`)으로 나감. 문서의 `{status,error,message}` 계약과 불일치 | common/ApiExceptionHandler.java:20-69 | M | 확인 |
| BE-2 | DataIntegrityViolation이 signup·태그·카테고리·identity 밖에서는 500. 예: 본문·검색어의 `\u0000`(NUL) | ApiExceptionHandler, AuthController.java:70-76 | M | 추정 |
| BE-3 | DB 장애·락 대기·교착이 모두 500이고 hikari connection-timeout, `lock_timeout`, `statement_timeout`이 없음(FOR UPDATE/SHARE 무한 대기) | application.properties, *Repository findForUpdate* | M | 확인/추정 |
| BE-4 | JSON 요청 본문 크기 제한 없음. 수십 MB를 다 읽은 뒤에야 400 | application.properties, PostRequests.java:33 | L-M | 추정 |
| BE-5 | 알 수 없는 JSON 필드를 조용히 무시(오타 PATCH가 200 무변경), 본문 타입 오류에 필드 정보 없음 | ApiExceptionHandler.java:46-49 | L | 확인 |
| BE-6 | 같은 종류의 파라미터 오류인데 코드가 `validation`과 `bad_request`로 나뉨. enum 대소문자 구분, 문서와 불일치 | ApiExceptionHandler.java:52-55, PostController.java:62-67, AdminUserController.java:37-42 | L | 확인 |
| BE-7 | Clock 빈과 `Instant.now()` 혼용(토큰, User, Post, LinkIntent 등) | TokenService.java:45, JwtTokenService.java:65-68 외 | L | 확인 |
| BE-8 | 운영 오설정에도 기동함(경고만): 공개된 dev JWT_SECRET이면 누구나 ADMIN JWT 위조 가능, OTP_HASH_KEY 기본값, COOKIE_SECURE=false, DB_PASSWORD 기본값 | JwtTokenService.java:73-75, PhoneOtpService.java:66-68, SmsConfiguration.java:20-24 | H | 확인 |
| BE-9 | CLIENT_IP_HEADER가 없으면 CloudFront 엣지 IP를 모두가 공유해서 IP 기준 OTP 한도가 사실상 서비스 거부가 됨 | ClientIpResolver.java:37-45, OtpRateLimiter.java:53-57 | M | 확인 |
| BE-10 | 정리 작업 부재: refresh_tokens, otp_challenges, post_views, 미사용 tags | 각 테이블 | M | 확인 |
| BE-11 | 세션 JDK 직렬화 비호환(Security 업그레이드) 시 기존 SESSION 쿠키의 모든 요청이 500 | AuthUserAuthentication.java:14, LinkIntent.java | L | 추정 |
| BE-12 | V8 `pg_trgm` 확장 생성 권한 요구사항이 문서에 없음 | V8__post_search_trgm.sql | L | 추정 |
| BE-13 | 로그인·가입·step-up에 레이트리밋 없음. 제한 세션이 `/auth/step-up/local`로 비밀번호를 무제한 추측할 수 있음(B4 우회) | StepUpController.java:61-67, AuthController.java:63-108 | H | 확인 |
| BE-14 | ADMIN_EMAIL 선점: 실제 관리자보다 먼저 그 이메일로 가입하면 ADMIN이 됨(이메일 소유 확인 없음) | LocalAccountService.java:64-66, AdminBootstrap.java:58-65 | H | 확인 |
| BE-15 | 만료된 Bearer 헤더가 붙으면 refresh·logout·공개 GET도 401(로그아웃해도 refresh family가 남음) | SecurityConfig.java:121-125, BearerRequests.java:19-23 | M | 확인 |
| BE-16 | 강등된 역할이 세션 수명(14일) 동안 남음(`/admin/**`, 숨김 글 조회) | AuthUser.java:20, SecurityConfig.java:140 | M | 확인 |
| BE-17 | 정지와 동시에 진행된 로그인의 세션·refresh가 살아남음(읽기 가능) | AdminUserService.java:164-182, AuthController.java:95-107 | L | 확인 |
| BE-18 | 정지 계정의 SESSION이 남은 브라우저에서는 다른 계정 로그인·가입·OTP가 모두 403 suspended | SecurityConfig.java:144 | L | 확인 |
| BE-19 | OAuth 성공 핸들러 안의 예외가 브라우저에 500 JSON으로 노출됨(콜백 302 안 함) | OAuth2LoginSuccessHandler.java:76-99,159-166 | M | 확인 |
| BE-20 | OAuth 실패와 매퍼 예외가 로그에 남지 않음(원인 소실) | OAuth2LoginFailureHandler.java:36-41, OAuth2LoginSuccessHandler.java:145-156 | M | 확인 |
| BE-21 | OAuth 제공자 HTTP 호출(token, userinfo, JWKS)에 타임아웃이 없음 | OAuth2LoginCustomizer.java:47-56 | M | 추정 |
| BE-22 | OTP 소모 후 계정 처리가 실패하면 OTP만 날아가고 쿨다운과 한도가 소모됨 | PhoneAuthController.java:100-111 | L | 확인 |
| BE-23 | OTP 한도를 0으로 설정하면 NPE로 500, 음수 검증 없음 | OtpRateLimiter.java:49-57, OtpProperties.java:24-34 | L | 확인 |
| BE-24 | End User Messaging 호출 타임아웃·재시도가 SDK 기본값이라 한 요청이 1분 넘게 스레드를 점유할 수 있음 | SmsConfiguration.java:28-35 | M | 추정 |
| BE-25 | 발송 국가·번호 유형 제한이 없어 SMS 비용 남용(pumping) 위험 | PhoneNumbers.java:31-45 | M | 확인 |
| BE-26 | console SMS 제공자가 기본값이라 운영에서 설정이 빠지면 OTP 원문과 번호가 INFO 로그에 남음 | ConsoleSmsSender.java:15, SmsProperties.java:21 | H | 확인 |
| BE-27 | 글 수정·삭제와 관리자 숨김·복구가 동시에 일어나면 잃어버린 갱신으로 숨김·삭제가 되살아남(@Version 없음) | Post.java, PostService.java:111-137 | M-H | 확인 |
| BE-28 | 조회수 집계가 실패하면 이미 성공한 상세 GET이 500 | PostController.java:79-88, PostViewService.java:37-50 | M | 확인 |
| BE-29 | 좋아요가 가시성 확인 후 잠그지 않아 숨김·삭제 직후에도 들어감. 글 행이 없으면 count에서 500 | PostLikeService.java:37-80 | L | 확인 |
| BE-30 | page 상한이 없어 깊은 OFFSET 스캔(공개 엔드포인트) | PostController.java:62-67, CommentController.java:43-48 | L | 확인 |
| BE-31 | 글·댓글 쓰기에 레이트리밋 없음(스팸) | PostController.java:91-97, CommentController.java:53-60 | L-M | 확인 |
| BE-32 | confirm 중 S3 오류(403, 5xx, SdkClientException)가 그대로 500 | AttachmentService.java:102-124, S3UploadStorage.java:62-81 | M | 확인 |
| BE-33 | confirm과 정리 작업이 겹치면 객체 없는 CONFIRMED 행, 또는 태그 없는 영구 고아 객체가 생김 | AttachmentService.java:120-123, PendingAttachmentCleanup.java:48-61 | M | 확인 |
| BE-34 | presign에 레이트리밋·할당량 없음 | AttachmentService.java:76-93 | M | 확인 |
| BE-35 | presign 트랜잭션 안에서 SDK 자격증명(IMDS)을 조회해 DB 커넥션을 점유, S3Client에 apiCallTimeout 없음 | AttachmentService.java:76-91, UploadConfiguration.java:68-74 | L | 추정 |
| BE-36 | 연결 해제된 첨부와 삭제·숨김 글의 이미지가 공개 URL로 계속 열림, 정리 없음 | AttachmentService.java:160-165 | L-M | 확인 |
| BE-37 | 정리 작업 분산 락 없음(다중 인스턴스에서 중복 실행) | PendingAttachmentCleanup.java:43-45 | L | 확인 |
| BE-38 | 정지 커밋 뒤 세션 삭제가 실패하면 관리자에게 500이 가고 세션은 남음 | AdminUserService.java:179-199 | L-M | 확인 |
| BE-39 | 관리자 글 조치에 잠금 없음(BE-27과 같은 잃어버린 갱신) | AdminPostService.java:65-68 | M | 확인 |
| BE-40 | 카테고리 sortOrder 범위 미검증, max+1 오버플로 | AdminCategoryRequests.java:20-31 | L | 확인 |
| BE-41 | AdminCategoryService.save가 모든 DataIntegrityViolation을 `slug_taken`으로 오인 | AdminCategoryService.java:72-79 | L | 확인 |

## 프론트 (FE)

| ID | 문제 | 위치 | 심각도 | 판정 |
|---|---|---|---|---|
| FE-1 | 오픈 리다이렉트: `?next=/%09/evil.com`(탭·개행)이 `safeNext`를 통과해 로그인 후 외부 사이트로 이동 | lib/auth.ts:65-68 | H | 확인 |
| FE-2 | JSON이 아닌 오류와 Spring 기본 오류가 `http_502`나 "Internal Server Error" 같은 원문으로 노출됨 | lib/api.ts:186-187, lib/errors.ts:79 | M | 확인 |
| FE-3 | 2xx인데 JSON이 아닌 응답이 그대로 데이터로 들어가서 렌더 중 TypeError | lib/api.ts:166-175 | M | 추정 |
| FE-4 | 오프라인이면 쿼리·뮤테이션이 일시정지되어 무한 로딩·비활성 버튼이 되고, 복귀 시 뒤늦게 전송됨 | lib/query.ts:46-53 | M | 확인 |
| FE-5 | 401이 나면 me만 null로 바꾸고 다른 캐시는 그대로라서 stale editable 버튼이 남음(TASK-18 이슈의 원인) | lib/query.ts:41-45 | M | 확인 |
| FE-6 | 세션 만료를 쓰기 시도 전까지 감지 못함(me 재조회 없음) | lib/auth.ts:26-38 | L-M | 확인 |
| FE-7 | 쿠키가 막힌 환경에서 CSRF 403 루프, 잘못된 안내 | lib/api.ts:114-151 | L | 확인 |
| FE-8 | 요청 타임아웃 없음 | lib/api.ts:157-164 | L | 확인 |
| FE-9 | 401이 나면 RequireAuth가 언마운트되어 작성 중 입력과 첨부가 유실됨 | components/guards.tsx:27-32 | H | 확인 |
| FE-10 | 로그아웃 요청이 실패해도 로그아웃된 것처럼 표시됨 | lib/auth.ts:56-62 | M | 확인 |
| FE-11 | me 로딩 실패 시 헤더가 비고 좋아요·댓글이 영구 비활성, 재시도 버튼 없음 | site-header.tsx:21, guards.tsx | L-M | 확인 |
| FE-12 | OAuth 콜백의 알 수 없는 오류 코드(`oauth2_failed`, 표준 OAuth 코드)가 일반 문구로 표시됨 | callback-view.tsx:31,50, errors.ts | M | 확인 |
| FE-13 | 콜백 실패 시 sessionStorage의 next가 남아 이후 로그인에 재사용되고, 돌아가기 링크에서 next가 유실됨 | callback-view.tsx:12-58 | L | 확인 |
| FE-14 | 가입 성공 후 자동 로그인이 실패하면 재제출 시 "이미 가입된 이메일" | signup-view.tsx:64-118 | L-M | 확인 |
| FE-15 | 정지된 사용자가 쓰던 댓글이 메시지 없이 사라짐(TASK-18 이슈) | comment-section.tsx:124-160 | H | 확인 |
| FE-16 | 로그인 수단 해제에 확인 절차가 없음(LOCAL이면 비밀번호도 삭제), 제한 세션에서도 활성 | account-view.tsx:94-135 | M | 확인 |
| FE-17 | `?linked=` 임의 문자열이 성공 메시지로 표시되고 새로고침 시 반복됨 | account-view.tsx:42-46 | L | 확인 |
| FE-18 | OTP 5회 실패로 챌린지가 폐기된 뒤 안내 없음 | phone-otp-form.tsx:63-124 | L | 확인 |
| FE-19 | 글 작성 직후 조회수 0(TASK-18 이슈, 생성 응답으로 캐시를 채움) | post-editor.tsx:69-73 | M | 확인 |
| FE-20 | 좋아요 롤백이 그 사이 갱신된 최신 데이터를 덮어씀, 404면 화면 갱신 없음 | lib/board.ts:148-162 | L | 추정 |
| FE-21 | 삭제가 404로 실패해도 화면 갱신 없음, 성공 후 재클릭 가능 | post-actions.tsx:19-44 | L | 확인 |
| FE-22 | 목록 쿼리 엣지: page int 오버플로, 범위 밖 page, 1글자 q일 때 전체 목록 노출, 긴 tag, 카테고리·태그 오류 무음 | board-query.ts:72, post-list-view.tsx | L | 확인 |
| FE-23 | 백그라운드 재조회가 실패하면 기존 데이터를 오류로 교체(수정 폼 유실) | post-detail-view.tsx:40-43, post-edit-view.tsx:21-24 | L | 확인 |
| FE-24 | 서버 필드 오류 키(attachmentIds, categorySlug 등)가 표시되지 않으면 아무 안내도 없음 | post-editor.tsx:77-92 | M | 확인 |
| FE-25 | 첨부 연결 오류 시 어떤 첨부가 문제인지 알 수 없음 | post-editor.tsx:194,336 | M | 확인 |
| FE-26 | 업로드 중단·정체 처리 없음(XHR timeout·abort·삭제 불가) | lib/uploads.ts:55-71, post-editor.tsx | M | 확인 |
| FE-27 | PUT 성공 후 confirm이 실패하면 재시도 불가, 고아 첨부 | lib/uploads.ts:49-51, post-editor.tsx:164-171 | L-M | 확인 |
| FE-28 | 페이지 이탈 경고 없음(작성 중·업로드 중), 히스토리가 없으면 취소가 동작 안 함 | post-editor.tsx:342 | M | 확인 |
| FE-29 | 저장 성공 직후 이중 제출 창(중복 글) | post-editor.tsx:69-73,339 | L | 추정 |
| FE-30 | 카테고리 목록 실패가 무음이고, 수정 시 선택 표시와 실제 전송값이 불일치 | post-editor.tsx:44-236 | L | 확인 |
| FE-31 | 제한 세션(step-up)에서도 작성 화면이 열려 저장 시점에야 403, step-up 오류 표시 컴포넌트가 제각각 | post-editor.tsx, comment-section.tsx, account-view.tsx:97 | L-M | 확인 |
| FE-32 | 관리 작업 중 401이 나면 다이얼로그와 사유가 유실되고, 강등되면 탭마다 권한 없음 표시 | guards.tsx:48-58, confirm-dialog.tsx | L | 확인 |
| FE-33 | 필터 결과가 줄어 현재 페이지가 범위 밖이 됨(관리자 페이저) | admin/pager.tsx:15-39 | L | 확인 |
| FE-34 | error.tsx, global-error.tsx, not-found.tsx 없음(영문 "Application error") | src/app/ | H | 확인 |
| FE-35 | 없는 경로에서 S3 NoSuchKey XML이 노출됨(404.html로 안 감) | frontend_cdn/functions/index_rewrite.js | M | 확인 |
| FE-36 | 성공·배너까지 모두 role="alert"이고, 편집기·댓글 필드 오류가 aria-describedby로 연결되지 않음 | ui/alert.tsx:30, post-editor.tsx:350-353 | L | 확인 |

## 인프라·운영 (OPS)

| ID | 문제 | 위치 | 심각도 | 판정 |
|---|---|---|---|---|
| OPS-1 | AZ를 바꾸면 빈 데이터 볼륨이 생성되어 운영 DB가 비어 버림(snapshot_id 입력 없음) | variables.tf:19-23, backend_host/main.tf:144-152 | H | 확인 |
| OPS-2 | user_data를 바꾸면 인스턴스가 stop/start되지만 스크립트는 재실행되지 않음 | backend_host/main.tf:175-184 | M | 추정 |
| OPS-3 | 인스턴스 교체 후 /opt/board 유실, INSTANCE_ID 변수 stale로 CI 배포 실패, 롤백 불가 | backend_host, deploy.yml:36-40, ci_oidc:85-92 | H | 확인 |
| OPS-4 | ACM DNS 검증이 대기 중이면 apply가 장시간 멈춤(NS 값을 볼 수 없음) | dns_cert/main.tf:24-57 | M | 확인/추정 |
| OPS-5 | state lock이 고착됐을 때의 force-unlock 문서 없음, bootstrap state가 로컬에만 있음 | backend.tf, bootstrap/README.md | L | 확인 |
| OPS-6 | destroy 함정: ecr_force_delete가 루트에 노출되지 않음, 공용 OIDC provider 삭제 위험 | frontend_cdn/variables.tf:33-37, ci_oidc:16-23 | M | 확인 |
| OPS-7 | 오리진 장애 시 CloudFront HTML 502/504와 5xx 캐시, 배포마다 수십 초 다운타임 | frontend_cdn/main.tf:192-243 | M | 추정 |
| OPS-8 | CloudFront 액세스 로그 없음 | frontend_cdn/main.tf:171-259 | L | 확인 |
| OPS-9 | 데이터 EBS가 마운트되지 않으면(nofail) 루트 디스크에 빈 DB가 초기화되어 데이터가 갈라짐 | user_data.sh.tftpl:20-24, docker-compose.prod.yml:25-28 | H | 확인 |
| OPS-10 | user_data 실패가 감지되지 않음, dnf 재시도 없음, compose가 latest이고 체크섬 없음 | user_data.sh.tftpl, first-deploy.md:70-82 | M | 확인 |
| OPS-11 | mkfs 대상 장치 오인 가능성(이름 기반 fallback, blkid 실패 구분 없음) | user_data.sh.tftpl:8-18 | L | 확인 |
| OPS-12 | 데이터·루트 디스크가 가득 차는 상황에 대비가 없음(모니터링, 실패한 배포 이미지 누적) | backend_host/variables.tf:33-43, remote-deploy.sh:55 | M | 확인 |
| OPS-13 | 메모리 한도 합계(1712MiB)가 t4g.small 2GiB에 비해 빠듯하고 swap 없음(OOM 위험) | docker-compose.prod.yml:35,55 | M | 추정 |
| OPS-14 | api가 무응답(hang)일 때 자동 복구 없음(healthcheck 없음) | docker-compose.prod.yml:42-60 | M | 확인 |
| OPS-15 | postgres 이미지 태그가 고정되지 않아 배포마다 DB가 재시작될 수 있음, Docker Hub pull 한도 | docker-compose.prod.yml:18, remote-deploy.sh:48-49 | M | 확인 |
| OPS-16 | `compose up -d`가 실패하면 set -e로 롤백과 로그 출력 없이 종료되어 전면 중단 | remote-deploy.sh:9,49,69 | H | 확인 |
| OPS-17 | 롤백이 새 compose·env 파일을 그대로 씀(이전 파일 백업 없음) | deploy-backend.sh:41-46, remote-deploy.sh:43-69 | M | 확인 |
| OPS-18 | 이미지 정리가 생성 시각 기준이라 롤백 대상 이미지가 지워질 수 있음 | remote-deploy.sh:54-68 | L | 확인 |
| OPS-19 | 호스트에서 동시 배포를 막는 잠금 없음(로컬과 CI 경쟁) | remote-deploy.sh | M | 확인 |
| OPS-20 | send-command 전달 타임아웃 미설정으로 지연 실행, 취소 처리와 ssm:CancelCommand 권한 없음 | deploy-backend.sh:57-92, ci_oidc:85-99 | M | 확인/추정 |
| OPS-21 | 폴링 오류(AccessDenied 등)를 무시해서 17분 대기 후 "status unknown" | deploy-backend.sh:82 | L | 확인 |
| OPS-22 | send-command 파라미터 크기(약 11.5KB)가 SSM 한도 안에 드는지 미검증 | deploy-backend.sh:35-55 | M | 추정 |
| OPS-23 | render-env가 필수 키 3개만 검사해서 잘못된 운영 설정으로도 정상 배포됨 | render-env.sh:40-42 | M | 확인 |
| OPS-24 | 되돌릴 수 없는 Flyway 마이그레이션과 ddl-auto=validate 조합이면 롤백도 실패, 배포 전 백업 없음 | application.properties:12, remote-deploy.sh:59-75 | H | 확인/추정 |
| OPS-25 | CI가 SSM_PARAMETER_PATH·LOG_GROUP·DEPLOY_DIR·DATA_DIR를 넘기지 않음(기본값 하드코딩) | deploy.yml:35-40, tf-outputs.sh | L | 확인 |
| OPS-26 | 백엔드는 성공하고 프론트가 실패하면 버전 불일치, 알림 없음 | deploy.yml:69-76 | M | 확인 |
| OPS-27 | 배포 후 CloudFront 경유 스모크를 돌리지 않음 | deploy.yml | L | 확인 |
| OPS-28 | 시크릿 회전 절차 없음(DB 비밀번호, JWT·OTP 키, OAuth secret), put-secrets 부분 실패 | put-secrets.sh, first-deploy.md:96-97 | M | 확인 |
| OPS-29 | AWS 변수가 없는 main push마다 빨간 실행, 변수가 일부만 있으면 ECR push 후 실패 | deploy.yml:8-48 | M | 확인 |
| OPS-30 | arm 러너 가용성 미확인, 대기열에서 무한 대기, 대안 없음 | deploy.yml:30 | M | 추정 |
| OPS-31 | actionlint 설치 실패가 늦게 엉뚱한 곳에서 드러남(process substitution), 무결성 검증 없음 | ci.yml:49-53 | L | 확인 |
| OPS-32 | Testcontainers·레지스트리 익명 pull 불안정(Docker Hub 한도, cgr.dev digest) | TestcontainersConfiguration.java, docker-compose.yml | M | 추정 |
| OPS-33 | Docker 빌드 캐시가 없어 느리고 Maven Central 장애에 취약 | Dockerfile, build-backend.sh | L | 확인 |
| OPS-34 | 배포 실패 알림 없음, 롤백된 상태와 전면 다운을 구분할 수 없음 | deploy.yml | M | 확인 |
| OPS-35 | 알람·모니터링 전무(상태 검사, CPU 크레딧, 디스크, 메모리, 5xx, DLM 실패) | infra 전체 | H | 확인 |
| OPS-36 | 백업 복원 런북·복원 시험 없음, pg_dump 없음, RPO 24시간 | backend_host/main.tf:227-253 | H | 확인 |
| OPS-37 | 로그 보존·범위 제한(cloud-init·journal·SSM 출력 미수집, non-blocking 드롭) | user_data.sh.tftpl:37-47 | L | 확인 |
| OPS-38 | build-diagrams --check가 graphviz·폰트 환경에 따라 실패, 차이 목록을 보여 주지 않음 | build-diagrams.py:25,161, repo_test.sh:145-149 | L | 확인 |
| OPS-39 | e2e.sh 정리: KILL로 격상하지 않음, 포트 기반 kill로 다른 프로세스를 죽일 수 있음, E2E_KEEP 이후 충돌 | scripts/e2e.sh:45-67 | L | 확인 |
| OPS-40 | test.sh가 lock 파일이 바뀌어도 node_modules를 갱신하지 않음 | scripts/test.sh:44 | L | 확인 |
| OPS-41 | Makefile이 `.env`를 전역 export해서 배포 스크립트에 섞임 | Makefile:2-3 | L | 추정 |

## draft 매핑

draft 제목과 포함 ID는 `backlog draft list`를 기준으로 한다. 모든 draft는 아래 공통 진행 방식을 따른다.
- **Plan-Review-Go**: 착수 전에 카드의 구현 계획을 채운다. 구현 후에는 독립 서브에이전트가 코드 리뷰를 한 번 하고, 지적 사항을 반영한 뒤 Done으로 옮긴다.
- **Red-Green-Refactor**: AC마다 실패하는 테스트 커밋(red), 최소 구현 커밋(green), 정리 커밋(refactor, 필요할 때만)을 나눈다.
- **PRG(Post/Redirect/Get)**: 폼 제출 흐름(글 작성·수정, 댓글, 가입, 로그인)은 성공하면 결과 페이지로 replace 이동해서, 새로고침이나 뒤로가기로 재제출되지 않게 한다. 이중 제출을 차단한다.
