# 프론트엔드 화면

Next.js 정적 export 프론트의 라우트·상태·테스트 규칙이다. 개요와 실행 방법은 [README](../README.md)에 있다. 이 문서는 TASK-18에서 README의 프론트 절을 옮긴 것이다.
E2E(Playwright)는 README의 [테스트](../README.md#테스트) 절과 `frontend/e2e/`를 본다.

## 프론트엔드 (TASK-13)

정적 export라 서버 컴포넌트에서 세션을 읽을 수 없다. 로그인 상태·가드는 모두 클라이언트(`useMe()` = `GET /api/auth/me`)에서 판단하고,
검색 파라미터(`?next`, `?error`, `?status`, `?linked`)는 `<Suspense>` 안의 클라이언트 컴포넌트가 `useSearchParams`로 읽는다. 동적 라우트는 쓰지 않는다.

```bash
make dev-db && make backend-run      # 터미널 1: DB + 백엔드(:8080)
make frontend-dev                    # 터미널 2: http://localhost:3000 (/api → :8080 프록시)
```

| 경로 | 내용 |
|---|---|
| `/login/` | 이메일·비밀번호, 휴대폰 OTP(인증번호 3분 카운트다운, 429면 `Retry-After` 초 동안 다시 받기 대기), 소셜 5종(`/api/oauth2/authorization/{p}`로 페이지 이동). `?next=`로 돌아갈 경로(같은 오리진만 — 아래 `safeNext`) |
| `/signup/` | 서버와 같은 검증(이메일 모양, 비밀번호 8자·72바이트·문자+숫자, 닉네임 2~20자), 409 `email_taken`, 가입 뒤 `/api/auth/login`으로 자동 로그인 |
| `/auth/callback/` | 소셜 로그인 착지. `?status=ok`면 me를 다시 받아 로그인 전에 기억한 경로(sessionStorage `auth.next`)로, `?error=`면 한국어 안내(oauth2_failed·표준 OAuth 코드 매핑, 모르는 코드는 콜백 전용 문구) |
| `/account/` | 로그인 수단 목록·해제(409 `last_identity`), 소셜 연결(`/api/me/identities/link/{p}` → `?linked=`/`?error=`. `linked`는 알려진 제공자 id만 알리고, 알린 뒤 URL에서 지운다), 휴대폰 연결(OTP `?link=true`), 제한 세션 다시 인증(LOCAL 비밀번호 또는 소셜 재로그인) |

- **API 클라이언트** `src/lib/api.ts`: `api(path, {method, body, query})`. 같은 오리진 `/api`, `credentials:'include'`. 쓰기 메서드는 `XSRF-TOKEN` 쿠키를
  `X-XSRF-TOKEN` 헤더로 보내고(쿠키가 없으면 `GET /api/auth/csrf` 먼저), 요청마다 쿠키를 다시 읽어 로그인·OTP·step-up·OAuth 뒤 회전을 따라간다.
  403 `csrf`면 토큰을 다시 받아 한 번 재시도한다. 오류는 `ApiError{status, code, message, fields?, retryAfter?}`.
  오류 본문의 `error`가 `^[a-z0-9_]+$`가 아니거나 JSON이 아니면(프록시 HTML, Spring 기본 오류) `code`는 `http_<상태>`, `message`는 상태 범위별
  한국어(`statusMessage`)다. 2xx인데 본문이 JSON이 아니면 `bad_response`로 실패시킨다(텍스트를 데이터로 넘기지 않는다). (TASK-28#2·#3)
- **TanStack Query** `src/lib/query.ts`: 쿼리 키는 `[도메인, ...세부]`(`queryKeys`). 4xx는 재시도하지 않는다. 401 `unauthorized`면 me 캐시를 `null`로 바꿔
  헤더·가드가 로그아웃 상태를 따라간다(401 `invalid_credentials`·`invalid_otp`는 제외). 로그인 계열 성공 뒤에는 `useSessionChanged()`로 me를 바꾸고 다른 쿼리를 초기화한다.
- **가드** `src/components/guards.tsx`: `RequireAuth`(익명 → `/login/?next=<경로>`), `RequireAdmin`(USER는 권한 없음 안내). 제한 세션이면 레이아웃의 배너가 `/account/#step-up`으로 안내한다.
- **오류 문장** `src/lib/errors.ts`: 백엔드·OAuth 오류 코드 → 한국어. `errorMessage`는 형식이 맞고 표에 있으면 표 문구, 표에 없으면 서버 `message`가
  한국어일 때만 그 문장, 아니면 상태 범위별 한국어를 쓴다 — 영어 원문·HTML·내부 코드는 화면에 나오지 않는다. docs/api.md의 코드는 모두 표에 있다
  (`errors.test.ts`가 전수 확인 — 백엔드 코드를 더하면 그 목록에도 더한다). OAuth 콜백 `?error=`는 `oauthErrorMessage(code, "login"|"link")`로,
  모르는 코드는 로그인·연결 각각의 콜백 전용 문구다. (TASK-28#2·#4)
- **이동 경로** `safeNext`(`src/lib/auth.ts`): `?next=`·sessionStorage `auth.next`는 원문에 제어 문자(탭·개행)·역슬래시·공백이 없고, `/`로 시작하거나 현재
  오리진의 절대 URL이며, `new URL(next, origin)`의 오리진이 같을 때만 쓴다. 쓰는 값은 `pathname+search+hash`뿐이고, 결과가 `//`로 시작하거나 경로를
  디코드했을 때 역슬래시·제어 문자·`//`가 나오면(`/%2F/evil.com`) 버린다. 로그인·휴대폰 로그인·소셜 시작·OAuth 콜백·가입이 모두 이 함수를 거친다. (TASK-28#1)
- **테스트**: Vitest + Testing Library + MSW(`src/test/server.ts`, 모르는 요청은 실패). 쿠키는 jsdom `document.cookie`로 흉내 낸다.

### 게시판 화면 (TASK-14)

| 경로 | 내용 |
|---|---|
| `/` | 게시판 목록. 카테고리 탭(`/api/categories`), 인기 태그 칩(`/api/tags?limit=20`), 검색(`q`), 정렬(최신순·인기순), 이전·다음 페이지. 상태는 모두 URL 쿼리 `?category&tag&q&sort=popular&page=<1부터>&includeHidden=true`에 있어서 링크를 공유하거나 뒤로 가기를 할 수 있다(기본값은 생략, API `page`는 0부터라 1을 뺀다). ADMIN은 "숨김·삭제 글 포함" 토글과 배지를 본다 |
| `/posts/view/?id=` | 상세. 본문은 텍스트 노드 + `white-space: pre-wrap`(HTML 해석 없음), 첨부 이미지 `attachments[].url`, 조회수, 좋아요(낙관적 반영, 실패 시 되돌림), 댓글 목록(삭제 자리표시)·작성·삭제. `editable`일 때만 수정·삭제 버튼 |
| `/posts/new/`, `/posts/edit/?id=` | 작성·수정(`RequireAuth`). 수정은 `PATCH`이고 `editable=false`면 폼 대신 권한 없음 안내 |

- **라우트**: 정적 export는 동적 세그먼트(`/posts/[id]`)를 미리 만들 수 없으므로 id를 쿼리 파라미터로 받는다. 뷰는 `<Suspense>` 안에서 `useSearchParams`로 읽는다. 잘못된 id는 요청 없이, 404는 응답을 보고 "없는 글"로 안내한다.
- **검색어**: 서버(`PostController.searchTerm`)와 같이 Java `strip()` 뒤 2~100자(코드 포인트). 비면 검색하지 않고, 1자는 요청하지 않고 안내한다.
- **태그 입력**: `src/lib/tags.ts`가 서버 `TagNames`와 같은 순서로 정규화한다(NFC → Java `strip` → ASCII 공백 축약 → 소문자 → 30자). 입력 중 "저장될 이름"을 미리 보여 주고, Enter·쉼표로 추가하며, 중복은 합치고 5개까지 받는다. 입력칸에 남은 값도 등록할 때 붙인다.
- **카테고리**: `adminOnly`(공지)는 ADMIN에게만 보인다. 서버도 403 `category_admin_only`로 막는다.
- **이미지 업로드** `src/lib/uploads.ts`: 타입(png·jpeg·webp·gif)·크기(5MB)·개수(10)를 먼저 확인한 뒤 presign → `uploadUrl`로 XHR `PUT`(받은 `headers`를 그대로 보내고, 쿠키·CSRF는 보내지 않는다. 진행률은 `upload.onprogress`) → confirm 순서로 진행한다. 확정된 id를 `attachmentIds`로 보낸다. 파일은 하나씩 올리고, 올리는 동안에는 등록 버튼을 막는다. S3 PUT 실패는 `upload_failed`다.
- **권한 UI**: 수정·삭제는 서버가 준 `editable`만 보고, 화면에서 역할이나 작성자를 다시 계산하지 않는다. 403 `forbidden`, 404 `post_not_found`·`comment_not_found`, 403 `step_up_required`(`/account/#step-up` 링크), 401(로그인 링크)은 `ApiErrorAlert`가 그 자리에 보여 준다. `RequireAuth`의 `next`에는 쿼리도 들어간다(`/posts/edit/?id=3`).
- **캐시**: 쿼리 키 `["posts", "list"|"detail"|"comments", …]`, `["categories"]`, `["tags", q]`. 좋아요를 누르면 상세 캐시만 바꾸고 목록은 stale로 표시한다. 저장하면 상세 캐시를 응답으로 채우고 목록·태그를 다시 받는다. 수정 화면도 상세 API를 읽으므로 조회수 규칙(30분에 1회)을 따른다.
- **E2E용 `data-testid`**: 목록 `post-list`·`post-item`·`post-item-link`·`category-tab`·`tag-chip`·`search-input`·`search-submit`·`sort-select`·`pagination-prev`/`next`·`new-post-link`. 상세 `post-title`·`post-content`·`post-attachments`·`view-count`·`like-button`·`like-count`·`post-edit-link`·`post-delete-button`·`comment-list`·`comment-item`·`comment-input`·`comment-submit`·`comment-delete`·`post-not-found`. 편집기 `post-editor`·`editor-title`·`editor-content`·`editor-category`·`editor-tag-input`·`editor-tag-preview`·`editor-tags`·`editor-file-input`·`editor-attachments`·`editor-upload-progress`·`editor-submit`.
- **로컬 업로드 확인**: `make dev-db && make dev-storage`를 실행하고, `example.env`의 `UPLOADS_*` 값으로 백엔드를 띄운다. 그다음 `make frontend-dev`를 실행한다. MinIO(:9000)가 `http://localhost:3000` 오리진의 PUT preflight를 허용한다.

### 관리자 화면 (TASK-15)

| 경로 | 내용 |
|---|---|
| `/admin/?tab=users` | 사용자 목록(최근 가입순, 20개씩). 검색(닉네임·이메일·전화), 상태 필터(활성·정지), 로그인 수단·상태 배지. 정지·정지 해제는 확인 다이얼로그에서 선택 사유(500자 이하)와 함께 보낸다. 409 `cannot_suspend_self`·`cannot_suspend_admin`은 다이얼로그에 한국어로 안내한다 |
| `/admin/?tab=posts` | `GET /api/posts?includeHidden=true`(게시판 목록 훅 재사용). 숨김·삭제 배지, 상세 링크, 숨김·숨김 해제·삭제 복구(사유 포함) |
| `/admin/?tab=categories` | 공개 `/api/categories` 목록 + 생성·수정·삭제. 폼은 서버 규칙과 같다(slug `^[a-z0-9-]{2,30}$`, 이름은 앞뒤 공백을 자르고 1~50자, 순서는 정수이며 생성 시 비우면 맨 뒤). 수정은 바뀐 필드만 `PATCH`. 409 `slug_taken` 안내 |
| `/admin/?tab=actions` | 감사 로그(`GET /api/admin/actions`) 최근순 표, 이전·다음 페이지 |

- **가드**: `page.tsx`가 `<Suspense>` + `RequireAdmin`으로 감싼다. 익명은 `/login/?next=/admin/?tab=…`로, `USER`는 403 안내(`admin-forbidden`)만 보고 관리 API는 부르지 않는다. 실제 차단은 서버가 한다.
- **탭**: `?tab=`(기본 `users`, 모르는 값도 `users`). 탭을 누르면 `router.replace`로 URL을 바꾼다. 선택된 탭만 그리므로 그 탭의 API만 호출한다. 탭 안의 검색·필터·페이지는 화면 상태다(URL에 넣지 않는다).
- **확인 다이얼로그**: `window.confirm` 대신 shadcn `alert-dialog`(Base UI, `role="alertdialog"`)를 쓴다. 실패하면 다이얼로그를 연 채로 오류를 보이고, 성공하면 닫는다. 빈 사유는 `{}` 본문으로 보낸다.
- **캐시**: 쿼리 키 `["admin", "users", query]`, `["admin", "actions", page]`. 관리 쓰기 뒤에는 `["admin", "actions"]`를 무효화한다. 정지·해제 뒤에는 `["admin", "users"]`, 글 처리 뒤에는 `["posts"]`, 카테고리 변경 뒤에는 `["categories"]`와 `["posts"]`(글의 카테고리 이름·slug가 바뀐다)도 무효화한다.
- **E2E용 `data-testid`**: `admin-view`·`admin-tab-{users|posts|categories|actions}`·`admin-forbidden`. 사용자 `admin-user-search-input`·`admin-user-search-submit`·`admin-user-status-filter`·`admin-user-row`·`admin-user-status`·`admin-user-provider`·`admin-user-role`·`admin-user-suspend`·`admin-user-unsuspend`·`admin-users-prev`/`next`. 글 `admin-post-row`·`admin-post-link`·`admin-post-hide`·`admin-post-unhide`·`admin-post-restore`. 카테고리 `admin-category-row`·`admin-category-admin-only-badge`·`admin-category-edit`·`admin-category-delete`·`admin-category-form`·`admin-category-slug`·`admin-category-name`·`admin-category-sort-order`·`admin-category-admin-only`·`admin-category-submit`·`admin-category-cancel`·`admin-category-{slug|name|sort-order}-error`·`admin-category-error`. 감사 로그 `admin-action-row`·`admin-actions-prev`/`next`. 다이얼로그 `admin-confirm-dialog`·`admin-reason-input`·`admin-confirm-submit`·`admin-confirm-cancel`·`admin-confirm-error`.
