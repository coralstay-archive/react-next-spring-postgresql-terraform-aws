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
| `/login/` | 이메일·비밀번호, 휴대폰 OTP(인증번호 3분 카운트다운, 429면 `Retry-After` 초 동안 다시 받기 대기, 같은 인증번호를 5번 틀리면 잠금 안내 — 아래 "휴대폰 OTP 잠금"), 소셜 5종(`/api/oauth2/authorization/{p}`로 페이지 이동). `?next=`로 돌아갈 경로(같은 오리진만 — 아래 `safeNext`) |
| `/signup/` | 서버와 같은 검증(이메일 모양, 비밀번호 8자·72바이트·문자+숫자, 닉네임 2~20자), 409 `email_taken`, 가입 뒤 `/api/auth/login`으로 자동 로그인하고 `next`로 replace 이동. 자동 로그인이 실패하면 폼 대신 "가입은 완료됐습니다" + 로그인 링크(`next` 유지)를 보여 재제출(409)을 막는다(TASK-29#8) |
| `/auth/callback/` | 소셜 로그인 착지. `?status=ok`면 me를 다시 받아 로그인 전에 기억한 경로(sessionStorage `auth.next`)로, `?error=`면 한국어 안내(oauth2_failed·표준 OAuth 코드 매핑, 모르는 코드는 콜백 전용 문구). 성공이든 실패든 `auth.next`는 한 번 읽고 지우며, 실패 시 "로그인으로 돌아가기" 링크의 `?next=`에 싣는다(TASK-29#7) |
| `/account/` | 로그인 수단 목록·해제(확인 다이얼로그, LOCAL 이면 비밀번호 삭제 경고, 409 `last_identity`는 다이얼로그 안에, 제한 세션이면 비활성 — 아래 "제한 세션(step-up)"), 소셜 연결(`/api/me/identities/link/{p}` → `?linked=`/`?error=`. `linked`는 알려진 제공자 id만 알린다. `linked`·`error`는 보여 준 뒤 URL에서 지우고 다른 쿼리와 hash는 남긴다), 휴대폰 연결(OTP `?link=true`), 제한 세션 다시 인증(LOCAL 비밀번호 또는 소셜 재로그인) |

- **API 클라이언트** `src/lib/api.ts`: `api(path, {method, body, query})`. 같은 오리진 `/api`, `credentials:'include'`. 쓰기 메서드는 `XSRF-TOKEN` 쿠키를
  `X-XSRF-TOKEN` 헤더로 보내고(쿠키가 없으면 `GET /api/auth/csrf` 먼저), 요청마다 쿠키를 다시 읽어 로그인·OTP·step-up·OAuth 뒤 회전을 따라간다.
  403 `csrf`면 토큰을 다시 받아 한 번 재시도한다. 오류는 `ApiError{status, code, message, fields?, retryAfter?}`.
  오류 본문의 `error`가 `^[a-z0-9_]+$`가 아니거나 JSON이 아니면(프록시 HTML, Spring 기본 오류) `code`는 `http_<상태>`, `message`는 상태 범위별
  한국어(`statusMessage`)다. 2xx인데 본문이 JSON이 아니면 `bad_response`로 실패시킨다(텍스트를 데이터로 넘기지 않는다). (TASK-28#2·#3)
  쿠키 차단(`cookies_blocked`)·쓰기 호출 35초 타임아웃(`timeout`)은 아래 "네트워크·오프라인·타임아웃"을 본다.
- **TanStack Query** `src/lib/query.ts`: 쿼리 키는 `[도메인, ...세부]`(`queryKeys`). 4xx는 재시도하지 않는다. 401 `unauthorized`면 me 캐시를 `null`로 바꿔
  헤더·가드가 로그아웃 상태를 따라간다(401 `invalid_credentials`·`invalid_otp`는 제외). 로그인 상태였다면 세션 만료로 처리한다(아래 "세션 만료·초안").
  로그인 계열 성공 뒤에는 `useSessionChanged()`로 me를 바꾸고 다른 쿼리를 초기화한다.
- **가드** `src/components/guards.tsx`: `RequireAuth`(처음부터 익명 → `/login/?next=<경로>`, me를 못 받으면 오류 + 다시 시도), `RequireAdmin`(USER는 권한 없음 안내).
  내용을 그린 뒤 세션이 끝나면 언마운트하지 않는다(아래, 단 내용 컴포넌트가 스스로 숨는 `/account/`는 예외). 제한 세션이면 레이아웃의 배너가 `/account/#step-up`으로 안내하고 작성 컨트롤을 미리 막는다(아래 "제한 세션(step-up)").
- **헤더**: me를 못 받으면(네트워크·5xx) 로그인·가입 링크가 아니라 "사용자 정보를 불러오지 못했습니다" + 다시 시도(사용자를 이미 알면 재조회 실패에도 표시 유지).
  로그아웃은 성공(204) 또는 401일 때만 로그아웃 상태로 바꾸고 초안을 모두 지운 뒤 `/`로 간다. 그 밖의 실패는 "로그아웃하지 못했습니다 …"를 보이고 상태를 그대로 둔다(TASK-29#5·#6).
- **오류 문장** `src/lib/errors.ts`: 백엔드·OAuth 오류 코드 → 한국어. `errorMessage`는 형식이 맞고 표에 있으면 표 문구, 표에 없으면 서버 `message`가
  한국어일 때만 그 문장, 아니면 상태 범위별 한국어를 쓴다 — 영어 원문·HTML·내부 코드는 화면에 나오지 않는다. docs/api.md의 코드는 모두 표에 있다
  (`errors.test.ts`가 전수 확인 — 백엔드 코드를 더하면 그 목록에도 더한다). OAuth 콜백 `?error=`는 `oauthErrorMessage(code, "login"|"link")`로,
  모르는 코드는 로그인·연결 각각의 콜백 전용 문구다. 연결 모드는 `OAUTH_LINK_MESSAGES`가 로그인을 말하는 문구(oauth2_failed·server_error·
  invalid_request·access_denied 등)를 "계정 연결" 문구로 덮는다. 입력칸 `fields` 문구는 백엔드 `ValidationMessageTest`가 모든 Bean Validation
  제약에 한국어 message가 있는지 확인한다. (TASK-28#2·#4)
- **이동 경로** `safeNext`(`src/lib/auth.ts`): `?next=`·sessionStorage `auth.next`는 원문에 제어 문자(탭·개행)·역슬래시·공백이 없고, `/`로 시작하거나 현재
  오리진의 절대 URL이며, `new URL(next, origin)`의 오리진이 같을 때만 쓴다. 쓰는 값은 `pathname+search+hash`뿐이고, 결과가 `//`로 시작하면 버린다. 이어서
  `pathname`을 `decodeURIComponent`로 한 번 디코드해, 디코드에 실패하거나 디코드한 경로가 `//`로 시작하거나(`/%2F/evil.com`) 역슬래시·제어
  문자를 담으면(`/%5Cevil.com`) 버린다. 이 디코드 검사에서는 공백을 지운 뒤 문자를 보므로, 인코딩된 공백(`%20`)은 경로에 남아도 된다(원문의 생 공백은 위에서 이미 거부). 로그인·휴대폰 로그인·소셜 시작·OAuth 콜백·가입이 모두 이 함수를 거친다. (TASK-28#1)
- **테스트**: Vitest + Testing Library + MSW(`src/test/server.ts`, 모르는 요청은 실패). 쿠키는 jsdom `document.cookie`로 흉내 낸다. 테스트마다 쿠키와 sessionStorage를 비운다.
  `make test`가 백엔드 빌드와 겹쳐도 파일의 첫 테스트(콜드 스타트)가 시간 초과로 깨지지 않게 `testTimeout`·`hookTimeout` 15초, 워커는 코어 절반(`vitest.config.mts`).

### 세션 만료·초안 (TASK-29)

- **만료 판정**: "로그인 상태였는데 세션이 끝났다"를 두 곳에서 본다. (1) me가 아닌 쿼리·뮤테이션의 401 `unauthorized`(`ApiError.isSessionExpired`). 틀린 비밀번호·인증번호(401 `invalid_*`)와
  계약 밖 401(`http_401`)은 아니다. (2) me 재조회가 사용자 → `null`(me는 401을 익명 `null`로 받으므로 오류가 아니다). 로그인·로그아웃이 `setQueryData`로 바꾼 것은 만료가 아니다.
  직전 me가 사용자였을 때만 처리하므로 처음부터 익명인 사용자에게는 안내가 뜨지 않고, 리셋 뒤 재조회가 다시 401이어도 반복하지 않는다.
- **만료 처리** (`createQueryClient`): me=`null`, `["auth", …]` 밖의 모든 쿼리 `resetQueries`(데이터를 버리고 화면에 떠 있는 것은 익명으로 다시 받는다 → 서버 `editable=false`라
  수정·삭제 버튼이 사라진다), `["auth","sessionExpired"]`=true → 레이아웃의 `SessionExpiredBanner`("세션이 만료되었습니다" + 현재 경로로 돌아오는 다시 로그인 링크 + 닫기).
  다시 로그인하면(me가 사용자) 플래그를 내린다.
- **계정 전환** (A→B, 리뷰 결정): me가 마지막으로 본 사용자(그사이 `null`이었어도)와 다른 사용자가 되면 — 다른 탭에서 B로 로그인한 뒤 이 탭이 me를 다시 받은 경우 —
  만료 안내 없이 `["auth", …]` 밖의 쿼리를 reset(A의 `editable`·`["me","identities"]`를 버림)하고 이 탭의 A 초안을 지운다(`clearDraftsOf`). `RequireAuth`는 내용을,
  댓글 폼은 편집기를 사용자 id를 key로 다시 마운트해 빈 상태로 연다. 초안 저장은 화면을 연 사용자로 주인을 고정하고, 쓰기 직전 me가 다른 사용자면 쓰지 않는다
  (A의 입력이 B의 초안·B의 등록으로 넘어가지 않는다).
- **me 재조회**: `useMe`는 창 포커스 때(기본 staleTime 30초 안이면 생략)와 탭이 보이는 동안 5분마다(`ME_REFETCH_INTERVAL`, 백그라운드 탭 제외) 다시 받는다.
- **작성 화면 유지**: `useSessionUser()`는 컴포넌트가 본 마지막 사용자를 기억한다. `RequireAuth`·`RequireAdmin`은 내용을 그린 뒤 세션이 끝나면 로그인으로 보내지 않고 내용을 그대로 둔 채
  화면 아래 고정 안내("로그인이 끊겼습니다" + 다시 로그인 링크)를 붙인다(트리 모양을 바꾸지 않아 입력·첨부·관리 다이얼로그 사유가 언마운트되지 않는다). `RequireAuth` 자신은
  내용을 언마운트하지 않지만, 내용 컴포넌트가 me를 보고 스스로 숨으면 사라진다: `/account/`(`account-view.tsx`의 `if (!me) return null`)는 만료 뒤 내용이 비고
  연결·step-up 입력과 해제 확인 다이얼로그가 사라진다(범위 밖 — TASK-32에서도 AC에 필요하지 않아 남겼다. 고치려면 하단 안내의 "임시 저장" 문구가 이 화면에 맞지 않는 문제도 함께 정해야 한다). 명시적 로그아웃 성공 뒤(`["auth","loggedOut"]`, 다시 로그인하면 내림)에는 "임시 저장되어 있습니다" 안내가 틀린 말이라
  안내 없이 내용을 비우고 `/`로 간다.
- **안내는 하나만** (리뷰 결정): 작성 화면이 하단 고정 안내를 띄운 동안에는 상단 `SessionExpiredBanner`(`["auth","sessionLostNotices"]` 수로 판단)와 그 안쪽
  `ApiErrorAlert`의 "로그인하기" 링크를 숨긴다. 다른 화면(상세의 댓글 폼 등)은 상단 배너와 오류 속 로그인 링크를 그대로 보인다. 수정 화면은 처음 받은
  editable 글로 편집기를 유지하고, 상세 화면은 리셋 뒤 다시 받는 동안 마지막 글을 `editable`·`likedByMe`=false로 보여 댓글 폼이 남는다. 댓글 폼은 처음부터 익명일 때만
  로그인 링크이고, 작성 중 401·403 `suspended`면 폼·입력·오류(401은 로그인 링크 포함)를 유지한다(TASK-18의 "정지 사용자 댓글이 메시지 없이 사라짐"의 원인).
- **초안** `src/lib/drafts.ts`(sessionStorage, 탭 단위): 키 `draft:post:new`, `draft:post:edit:<글 id>`, `draft:comment:<글 id>`, 값 `{v:1, userId, savedAt, data}`.
  화면은 `useDraftAutosave`(`src/hooks/use-draft-autosave.ts`)로 입력이 400ms 멈춘 뒤 한 번 저장하고(키 입력마다 직렬화·정리를 하지 않는다), 언마운트·`pagehide`·제출 직전에는
  바로 저장한다. 빈 값·원래 값과 같으면 바로 지운다. 다시 열면 같은 사용자일 때만 복원한다(글 편집기는 "임시 저장된 초안을 불러왔습니다" + 초안 버리기).
  글 초안은 제목·본문·카테고리·태그·업로드가 끝난 첨부. 직렬화 20만 자를 넘으면 저장하지 않고, 최대 20개(쓰기 전에 오래된 것부터 지움). 용량 초과면 그 키의 옛 버전을 지우고
  한 번 더 쓰고, 그래도 안 되면 그 키를 비운다(옛 버전이 최신 입력처럼 복원되지 않게). 등록·저장 성공, 명시적 로그아웃 성공, 계정 전환(A의 것) 때 지운다.
- **수정 초안 충돌** (리뷰 결정): 수정 초안은 바탕 글의 `updatedAt`(`baseUpdatedAt`)을 함께 담는다. 복원할 때 서버 글이 더 새로우면(또는 시각을 모르는 이전 형식이면)
  "이 글은 그사이 수정되었습니다 …" 경고와 "초안 유지"(지금 서버 글을 바탕으로 기억, 다음엔 경고 없음)·"초안 버리고 최신 글 불러오기"를 보인다. 고르기 전에는 기억한
  시각을 바꾸지 않아 다시 열어도 경고가 남는다. 덮어쓸지는 사용자가 정한다(서버의 낙관적 잠금은 없다).
- **이중 제출(PRG)**: 가입·댓글은 같은 틱의 두 번째 제출을 ref로 막는다. 가입 성공은 replace 이동. 글 작성·수정의 replace 이동과 성공 뒤 버튼 비활성 유지는 TASK-31 범위다.

### 네트워크·오프라인·타임아웃 (TASK-30)

- **오프라인** (FE-4): 레이아웃 상단 `OfflineBanner`("오프라인입니다" — 목록·글은 연결되면 다시 불러오지만 등록·저장은 직접 다시 시도). 상태는 TanStack
  `onlineManager`(브라우저 `online`·`offline` 이벤트)를 그대로 쓴다. 처음 값은 온라인이다 — TanStack v5 기본처럼 `navigator.onLine`은 읽지 않는다(크로미움에서
  거짓 오프라인이 있어, 그 값으로 쿼리가 멈추면 연결돼 있어도 화면이 영영 로딩이 된다).
- **뮤테이션 `networkMode: "always"`**: 기본 `"online"`은 오프라인 제출을 일시정지(버튼만 비활성)했다가 연결되면 사용자 모르게 보냈다. 지금은 바로 fetch 해서
  `network` 오류("서버에 연결할 수 없습니다")로 끝나고, 연결돼도 다시 보내지 않는다(B1). 입력·초안은 남으므로 사용자가 다시 누른다.
  **쿼리는 기본 `"online"`** 그대로다: 오프라인이면 재시도·재조회가 멈췄다가 연결되면 다시 받는다(읽기라 뒤늦게 보내도 안전). 이미 받은 데이터는 그대로 보인다.
- **쿠키 차단** (FE-7): 쓰기 요청 전 `GET /api/auth/csrf`를 받은 뒤에도 `XSRF-TOKEN` 쿠키가 없으면 요청을 보내지 않고 `ApiError` 403 `cookies_blocked`
  ("브라우저가 쿠키를 막고 있어 … 쿠키를 허용한 뒤 다시 시도")로 끝낸다. 예전에는 토큰 없이 보내 403 `csrf` → 다시 받아도 없음 → "보안 토큰이 만료"만 되풀이했다.
  `cookies_blocked`·`timeout`은 서버가 보내지 않는 클라이언트 코드다.
- **쓰기 요청 타임아웃** (FE-8, 유저 결정): POST/PUT/PATCH/DELETE는 호출마다 35초(`MUTATION_TIMEOUT_MS`, 백엔드 30초 예산보다 길게 — 서버가 끝까지
  처리하고 답한 것을 먼저 끊지 않는다). 지나면 fetch를 끊고 `ApiError` 0 `timeout`("응답이 너무 늦어 요청이 처리됐는지 확인하지 못했습니다. 저장·변경됐는지
  확인한 뒤 다시 시도하세요"). 자동 재시도는 없다(두 번 저장될 수 있다). 35초는 호출 전체 예산이다(유저 결정): 첫 시도·403 `csrf` 뒤 토큰 재발급·재시도를 타이머 하나가 덮고, 재시도는 남은 시간만 쓴다(재시도 경로의 토큰 GET은 한 번). 여러 쓰기가 공유하는 CSRF 토큰 GET에는 따로 10초(`CSRF_TOKEN_TIMEOUT_MS`) 제한이 있어, 멈추면 끊고 `network`로 끝낸다(쓰기는 아직 보내지 않았으므로 '처리 여부 확인'이 아니라 연결 오류 안내). 공유 요청은 실패해도 비워지므로 다음 쓰기는 토큰을 새로 받는다. 호출자 `signal` 취소는 `timeout`과 구분해
  AbortError 그대로 전달한다(내부 AbortController 하나 + 리스너·타이머, `AbortSignal.any`에 기대지 않는다). 조회(GET)와 S3 직접 업로드(XHR, TASK-31)에는
  걸지 않는다. 시간 초과 때도 편집기·입력·초안(TASK-29)은 그대로다.
- **재조회 실패** (FE-23): 데이터를 이미 받았으면 재조회가 실패해도 화면을 오류로 바꾸지 않고 위에 작은 `RefetchErrorAlert`("최신 내용을 불러오지 못했습니다.
  이전에 받은 내용을 보여 줍니다." + 오류 문장 + 다시 시도)만 붙인다. 데이터가 없을 때만 기존 오류 화면이다. 목록·상세·댓글·수정(편집기를 열기 전)·관리자 탭 4개·
  내 계정 로그인 수단에 적용한다. 상세는 세션 만료 리셋 뒤 다시 받기가 실패해도 마지막 글(익명 표시)과 댓글 폼·입력을 유지한다. 재조회가 404면(그사이 삭제)
  받은 내용이 있어도 없는 글 안내로 바꾼다.
  관리자 탭에서 페이지를 옮기다 실패하면 이전 페이지를 두지 않고 전체 오류 화면이다 — `keepPreviousData`는 새 페이지를 받는 동안(pending)에만 이전 데이터를 보여 주고, 실패하면 그 키에는 받은 데이터가 없기 때문이다(받아들인 동작, 다시 시도로 복구).

### 게시판 화면 (TASK-14)

| 경로 | 내용 |
|---|---|
| `/` | 게시판 목록. 카테고리 탭(`/api/categories`), 인기 태그 칩(`/api/tags?limit=20`), 검색(`q`), 정렬(최신순·인기순), 이전·다음 페이지. 상태는 모두 URL 쿼리 `?category&tag&q&sort=popular&page=<1부터>&includeHidden=true`에 있어서 링크를 공유하거나 뒤로 가기를 할 수 있다(기본값은 생략, API `page`는 0부터라 1을 뺀다). ADMIN은 "숨김·삭제 글 포함" 토글과 배지를 본다 |
| `/posts/view/?id=` | 상세. 본문은 텍스트 노드 + `white-space: pre-wrap`(HTML 해석 없음), 첨부 이미지 `attachments[].url`, 조회수, 좋아요(낙관적 반영, 실패 시 되돌림), 댓글 목록(삭제 자리표시)·작성·삭제. `editable`일 때만 수정·삭제 버튼 |
| `/posts/new/`, `/posts/edit/?id=` | 작성·수정(`RequireAuth`). 수정은 `PATCH`이고 `editable=false`면 폼 대신 권한 없음 안내 |

- **라우트**: 정적 export는 동적 세그먼트(`/posts/[id]`)를 미리 만들 수 없으므로 id를 쿼리 파라미터로 받는다. 뷰는 `<Suspense>` 안에서 `useSearchParams`로 읽는다. 잘못된 id는 요청 없이, 404는 응답을 보고 "없는 글"로 안내한다.
- **검색어**: 서버(`PostController.searchTerm`)와 같이 Java `strip()` 뒤 2~100자(코드 포인트). 비면 검색하지 않고, 1자는 요청하지 않고 안내한다.
  URL의 `q`가 이 범위 밖이면(직접 고친 링크 등) `q` 없이 요청하고 "검색어 무시: 검색어는 2~100자여야 합니다 검색어 없이 목록을 보여 줍니다."(`search-error`)를 보인다(TASK-33#2, FE-22).
- **목록 page** (TASK-33#2·#4, FE-22): URL `page`는 1 이상의 정수 문자열만 받고(음수·소수·`1e3`·문자는 1쪽) 서버 OFFSET 상한(`page × size ≤ 10,000`, TASK-23#5 —
  20개씩이면 API page 0~500 = 1~501쪽, `MAX_LIST_PAGE`)을 넘으면(자릿수가 넘쳐도) 501쪽으로 잘라 요청하고 `page-cap-notice`로 이유와 "검색·카테고리·태그로 좁혀 보라"를 알린다.
  응답의 `totalPages`보다 큰 쪽이면 `router.replace`로 마지막 쪽(0건이면 1쪽)으로 URL을 바로잡는다. "다음"은 `min(totalPages, 501)`까지만 있고, 501쪽인데 더 있으면
  `pagination-cap` 안내를 보인다. 목록 오류(받은 데이터 없음)에는 필터를 모두 뺀 `/`로 가는 "첫 페이지로"(`post-list-first-page`, 이미 `/`면 없음)를 붙인다.
  필터까지 빼는 것은 결정이다(TASK-33 리뷰): 오류의 원인이 필터 값(잘못된 카테고리·태그 등)일 수 있어 필터를 남긴 1쪽은 같은 오류를 되풀이할 수 있다.
- **카테고리 탭·인기 태그 오류** (TASK-33#2): 받은 데이터가 없이 실패하면 그 자리에 "카테고리를 불러오지 못했습니다."(`category-tabs-error`, "전체" 탭은 그대로)·
  "인기 태그를 불러오지 못했습니다."(`tag-chips-error`) + 다시 시도를 조용한 안내(alert 아님)로 보인다.
- **좋아요 롤백** (TASK-33#1, FE-20): 실패하면 최신 상세 캐시에 함수형 업데이트로 `likedByMe`·`likeCount`만 누르기 전 값으로 돌린다 — 요청 중 다시 받은 제목·본문·조회수는
  덮지 않는다(예전에는 누르기 전 스냅숏 전체로 되돌렸다). 단 캐시가 아직 낙관적 값(`likedByMe`·`likeCount` 모두)일 때만 되돌린다 — 그사이 재조회가 서버 값을
  넣었으면 옛 값으로 덮지 않고 상세를 다시 받는다. 404(그사이 삭제·숨김)면 상세를 다시 받아 없는 글 안내로 바꾼다.
- **태그 입력**: `src/lib/tags.ts`가 서버 `TagNames`와 같은 순서로 정규화한다(NFC → Java `strip` → ASCII 공백 축약 → 소문자 → 30자). 입력 중 "저장될 이름"을 미리 보여 주고, Enter·쉼표로 추가하며, 중복은 합치고 5개까지 받는다. 입력칸에 남은 값도 등록할 때 붙인다.
- **카테고리**: `adminOnly`(공지)는 ADMIN에게만 보인다. 서버도 403 `category_admin_only`로 막는다. 목록을 못 받으면 칸 아래에 "카테고리 목록을 불러오지 못했습니다" + 다시 시도(`editor-categories-error`)를 보인다. 지금 값(수정 중인 글의 카테고리·복원한 초안)이 목록에 없어도(실패·로딩 중·그사이 삭제) 선택지로 남겨 표시와 전송값이 같다(TASK-31#8, FE-30).
- **이미지 업로드** `src/lib/uploads.ts`: 타입(png·jpeg·webp·gif)·크기(5MB)·개수(10)를 먼저 확인한 뒤 presign → `uploadUrl`로 XHR `PUT`(받은 `headers`를 그대로 보내고, 쿠키·CSRF는 보내지 않는다. 진행률은 `upload.onprogress`) → confirm 순서로 진행한다. 확정된 id를 `attachmentIds`로 보낸다. 파일은 하나씩 올리고, 올리는 동안에는 등록 버튼을 막는다. S3 PUT 실패는 `upload_failed`다. 정체·취소·confirm 재시도는 아래 "편집기 견고성".
- **권한 UI**: 수정·삭제는 서버가 준 `editable`만 보고, 화면에서 역할이나 작성자를 다시 계산하지 않는다. 403 `forbidden`, 404 `post_not_found`·`comment_not_found`, 403 `step_up_required`(`StepUpNotice`, `/account/#step-up` 링크), 401(로그인 링크)은 `ApiErrorAlert`가 그 자리에 보여 준다. `RequireAuth`의 `next`에는 쿼리도 들어간다(`/posts/edit/?id=3`).
- **캐시**: 쿼리 키 `["posts", "list"|"detail"|"comments", …]`, `["categories"]`, `["tags", q]`. 좋아요를 누르면 상세 캐시만 바꾸고 목록은 stale로 표시한다. 작성에 성공하면 상세 캐시를 채우지 않고(생성 응답의 조회수는 이번 조회 전 값 0이라, 채우면 상세가 0을 보였다 — TASK-18 이슈, FE-19) 상세 화면이 다시 받는다. 수정 성공은 응답으로 채우되 stale로 표시해 상세가 열리면 다시 받는다. 둘 다 목록·태그를 다시 받는다. 수정 화면도 상세 API를 읽으므로 조회수 규칙(30분에 1회)을 따른다.
- **E2E용 `data-testid`**: 목록 `post-list`·`post-item`·`post-item-link`·`category-tab`·`tag-chip`·`search-input`·`search-submit`·`sort-select`·`pagination-prev`/`next`·`pagination-cap`·`page-cap-notice`·`search-error`·`post-list-error`·`post-list-first-page`·`category-tabs-error`·`tag-chips-error`·`new-post-link`. 상세 `post-title`·`post-content`·`post-attachments`·`view-count`·`like-button`·`like-count`·`post-edit-link`·`post-delete-button`·`comment-list`·`comment-item`·`comment-input`·`comment-submit`·`comment-delete`·`post-not-found`. 편집기 `post-editor`·`editor-title`·`editor-content`·`editor-category`·`editor-tag-input`·`editor-tag-preview`·`editor-tags`·`editor-file-input`·`editor-attachments`·`editor-upload-progress`·`editor-submit`, 오류 `editor-error`(폼 위)·`editor-title-error`·`editor-content-error`·`editor-category-error`·`editor-tags-error`·`editor-attachments-error`·`editor-attachment-problem`·`editor-categories-error`.
- **편집기 견고성 (TASK-31)**:
  - **PRG·이중 제출** (FE-29, B1 한 번의 저장 의도 = 글 하나): 제출은 ref로 잠근다(`isPending`은 다시 그려진 뒤에야 버튼을 막아, 같은 틱의 Enter 연타가 요청 여러 개가 됐다). 실패하면 풀고, 성공하면 잠금과 버튼 비활성을 둔 채 `router.replace`로 상세에 간다 — 편집기가 히스토리에서 빠져 뒤로 가기로 돌아와 다시 제출하지 않는다. 초안은 성공 때 지운다. 삭제도 성공하면 `replace("/")`와 버튼 비활성(FE-21).
  - **404·403**: 수정 저장·삭제가 404·403이면 상세 캐시를 무효화해 다시 받는다(삭제 404 → 없는 글 안내, 403 → 수정·삭제 버튼이 사라짐).
  - **서버 필드 오류** (FE-24): `fields` 키의 첫 이름으로 칸을 정한다 — `title`·`content`, `categorySlug`→카테고리, `tags`·`tags[n]`→태그, `attachmentIds`·`attachmentIds[n]`→첨부(`attachmentIds[n]`이 보낸 n번째 첨부로 풀리면 그 항목에만 보이고 첨부 칸에는 한 번 더 보이지 않는다. 맨 `attachmentIds`나 풀리지 않는 n만 첨부 칸 아래). 폼에 없는 키(오타 필드 `titel` 등)가 남으면 폼 위 알림(`editor-error`)에 그 메시지를 보인다.
  - **첨부 연결 오류** (FE-25): `unknown_attachment`·`attachment_not_confirmed`·`attachment_in_use`(·`attachment_expired`·`attachment_not_found`·`attachment_forbidden`)면 보낸 id 중 문제 첨부를 찾아 그 칸에 표시하고 "문제 이미지 빼고 다시 저장"(남은 첨부로 바로 다시 보냄)을 준다. 찾는 순서는 `fields`의 `attachmentIds[n]`(서버가 글 연결 오류마다 담는다, `attachment_forbidden` 포함 — `docs/api.md` 첨부), 그다음 `fields`가 없는 옛 응답 모양의 호환으로 서버 메시지 끝의 id다. 못 찾으면 첨부 칸 아래에 보인다.
  - **업로드 정체·상한·취소** (FE-26, 유저 결정): PUT은 XHR이다 — `fetch`에는 업로드 진행 이벤트가 없어 "진행이 멈췄다"를 알 수 없다. 진행 이벤트가 30초(`UPLOAD_STALL_MS`) 동안 없으면 `upload_stalled`, 시작부터 5분(`UPLOAD_MAX_MS`, presigned URL 만료)을 넘기면 `upload_timeout`으로 끊는다(둘 다 서버가 보내지 않는 클라이언트 코드). 올리는 중·차례를 기다리는 항목은 "업로드 취소"(대기 중이면 presign도 안 함), 실패 항목은 "빼기". 편집기가 언마운트되면 진행·대기 업로드를 모두 abort한다. 다시 올리면 presign부터 새로 한다(같은 URL 재PUT은 `If-None-Match: *`라 412).
  - **confirm만 다시** (FE-27): PUT은 됐는데 confirm이 연결·시간 초과·5xx(`storage_unavailable`, 첨부는 PENDING 그대로)·429로 실패하면 그 항목에 "확인 다시 시도"(`confirmAttachment`, 다시 올리지 않음 — 새로 올리면 지금 객체는 PENDING 고아가 된다). 410·404·400(만료·정리됨·불일치)은 다시 올리라고 안내한다. confirm만 남은 항목도 10개 자리를 차지한다.
  - **이탈 경고** (FE-28, B2 저장하지 않은 입력이 경고 없이 버려지지 않는다): `useLeaveGuard`.
    - 탭 닫기·새로고침·주소 이동(`beforeunload`): 입력이 원래와 다르거나 업로드·저장 중이면 브라우저 확인을 띄운다. 탭을 닫으면 sessionStorage 초안도 사라지기 때문이다.
    - 앱 안 링크(next/link 포함)·취소 버튼: 글자 입력만 있으면 묻지 않는다 — 초안(TASK-29)에 남아 같은 편집기를 다시 열면 복원되므로 버려지지 않는다. 업로드 중(떠나면 끊긴다)·저장 중·초안 저장 실패(저장 공간·20만 자 초과)일 때만 `window.confirm`으로 묻는다. 저장에 성공해 이동하는 중에는 묻지 않는다.
    - 저장 중 떠나기를 확인하면: 요청은 취소되지 않고 끝나며(성공하면 초안을 지우고 목록·태그 캐시를 무효화한다) 상세로 끌고 가지 않는다 — 상세 이동(`router.replace`)은 `save.mutate(body, { onSuccess })` 호출 단계 콜백이라 편집기가 언마운트되면 TanStack이 부르지 않는다(훅 단계 `onSuccess`는 언마운트 뒤에도 불린다). 경고 문구도 이대로 알린다.
    - App Router에는 이동을 막는 라우터 이벤트가 없어서 document의 capture 단계 click으로 같은 오리진 `<a href>`를 본다(React 루트보다 먼저 받아 거절하면 `preventDefault` + `stopPropagation`). 새 탭·수정키·다운로드·같은 페이지 `#`은 묻지 않는다.
    - 한계(유저 수용): 브라우저 뒤로·앞으로(popstate)와 코드의 `router.push`(로그아웃 등)는 막지 못한다. 글자는 초안으로 남고, 진행 중 업로드는 끊긴다.
    - 한계(유저 수용): 바로 앞 히스토리가 앱 밖(다른 사이트에서 링크로 들어옴)일 때 업로드·저장 중 취소하면 확인을 두 번 받는다 — 앱의 `window.confirm` 뒤 `router.back()`이 문서를 떠나며 `beforeunload` 브라우저 확인이 한 번 더 뜬다. 앞 항목이 앱 안인지 알 방법이 없어 그대로 둔다.
    - 취소: 히스토리가 없으면(새 탭으로 바로 연 편집기 — 예전에는 `router.back()`이 아무 일도 안 했다) `replace("/")`.
- **로컬 업로드 확인**: `make dev-db && make dev-storage`를 실행하고, `example.env`의 `UPLOADS_*` 값으로 백엔드를 띄운다. 그다음 `make frontend-dev`를 실행한다. MinIO(:9000)가 `http://localhost:3000` 오리진의 PUT preflight를 허용한다.

### 관리자 화면 (TASK-15)

| 경로 | 내용 |
|---|---|
| `/admin/?tab=users` | 사용자 목록(최근 가입순, 20개씩). 검색(닉네임·이메일·전화), 상태 필터(활성·정지), 로그인 수단·상태 배지. 정지·정지 해제는 확인 다이얼로그에서 선택 사유(500자 이하)와 함께 보낸다. 409 `cannot_suspend_self`·`cannot_suspend_admin`은 다이얼로그에 한국어로 안내한다. 정지 응답의 `warnings`는 아래 "정지 경고" |
| `/admin/?tab=posts` | `GET /api/posts?includeHidden=true`(게시판 목록 훅 재사용). 숨김·삭제 배지, 상세 링크, 숨김·숨김 해제·삭제 복구(사유 포함) |
| `/admin/?tab=categories` | 공개 `/api/categories` 목록 + 생성·수정·삭제. 폼은 서버 규칙과 같다(slug `^[a-z0-9-]{2,30}$`, 이름은 앞뒤 공백을 자르고 1~50자, 순서는 0~10000 정수이며 생성 시 비우면 맨 뒤 — TASK-33#6, TASK-27#4. 수정에서 순서를 바꾸지 않으면(숫자로 비교 — `020000`은 20000과 같다) PATCH에 빠지므로 범위 밖 옛 값도 막지 않는다). 수정은 바뀐 필드만 `PATCH`. 409 `slug_taken` 안내 |
| `/admin/?tab=actions` | 감사 로그(`GET /api/admin/actions`) 최근순 표, 이전·다음 페이지 |

- **가드**: `page.tsx`가 `<Suspense>` + `RequireAdmin`으로 감싼다. 익명은 `/login/?next=/admin/?tab=…`로, `USER`는 403 안내(`admin-forbidden`)만 보고 관리 API는 부르지 않는다. 실제 차단은 서버가 한다.
- **탭**: `?tab=`(기본 `users`, 모르는 값도 `users`). 탭을 누르면 `router.replace`로 URL을 바꾼다. 선택된 탭만 그리므로 그 탭의 API만 호출한다. 탭 안의 검색·필터·페이지는 화면 상태다(URL에 넣지 않는다).
- **페이저 범위** (TASK-33#3·#4, FE-33): 처리·재조회로 결과가 줄어 지금 쪽이 `totalPages` 밖이 되면 마지막 쪽(0건이면 첫 쪽)으로 옮긴다(`keepPreviousData`로 이전 쪽
  데이터를 보여 주는 동안은 그 totalPages가 지금 쪽 것이 아니라 옮기지 않는다). 관리자 목록도 서버 OFFSET 상한이 있어(관리자라고 상한을 풀지 않는 것은 결정 — TASK-33 리뷰) "다음"은 501쪽(20개씩)까지만 열리고, 거기서 더 있으면
  `<testId>-cap` 안내("검색·필터로 좁혀 보세요")를 보인다.
- **정지 경고** (TASK-33#5, TASK-27#3): 정지 응답 `warnings`에 `session_revoke_failed`가 있으면 다이얼로그를 닫은 뒤 목록 위에 `admin-suspend-warning`을 띄운다 — 정지는 적용됐지만
  로그인 세션을 모두 끊지 못했을 수 있고(남은 세션도 쓰기는 403 `suspended`, 읽기는 세션이 끝날 때까지 될 수 있다), 다시 시도하면 정지 상태는 그대로 두고 세션만 다시 끊는다는 안내.
  "세션 끊기 다시 시도"(`admin-suspend-retry`, `session_revoke_failed`가 있을 때만)는 정지를 다시 보내지 않고 세션 끊기 전용 API `POST /api/admin/users/{id}/sessions/revoke`
  (같은 사유, 멱등, 감사 로그 `USER_SESSIONS_REVOKE` — `docs/api.md`)를 부른다(결정 — TASK-33 리뷰: 정지 재전송은 감사 로그에 정지가 한 번 더 남고, 그사이 해제된 사용자를 다시 정지할 수 있다).
  최신 목록에서 그 사용자가 더는 정지 상태가 아니면 부르지 않고 안내를 닫는다. 응답 `warnings`가 비면 닫히고, 또 실패면 안내가 남는다.
  모르는 경고 코드는 코드 없이 "서버가 확인이 필요한 경고를 보냈습니다" 문장만 보이고 코드는 `console.warn`으로 남긴다. "닫기"(`admin-suspend-warning-close`).
- **확인 다이얼로그**: `window.confirm` 대신 shadcn `alert-dialog`(Base UI, `role="alertdialog"`)를 쓴다. 실패하면 다이얼로그를 연 채로 오류를 보이고, 성공하면 닫는다. 빈 사유는 `{}` 본문으로 보낸다.
- **캐시**: 쿼리 키 `["admin", "users", query]`, `["admin", "actions", page]`. 관리 쓰기 뒤에는 `["admin", "actions"]`를 무효화한다. 정지·해제 뒤에는 `["admin", "users"]`, 글 처리 뒤에는 `["posts"]`, 카테고리 변경 뒤에는 `["categories"]`와 `["posts"]`(글의 카테고리 이름·slug가 바뀐다)도 무효화한다.
- **E2E용 `data-testid`**: `admin-view`·`admin-tab-{users|posts|categories|actions}`·`admin-forbidden`. 사용자 `admin-user-search-input`·`admin-user-search-submit`·`admin-user-status-filter`·`admin-user-row`·`admin-user-status`·`admin-user-provider`·`admin-user-role`·`admin-user-suspend`·`admin-user-unsuspend`·`admin-users-prev`/`next`·`admin-users-cap`·`admin-suspend-warning`·`admin-suspend-retry`·`admin-suspend-warning-close`. 글 `admin-post-row`·`admin-post-link`·`admin-post-hide`·`admin-post-unhide`·`admin-post-restore`·`admin-posts-prev`/`next`·`admin-posts-cap`. 카테고리 `admin-category-row`·`admin-category-admin-only-badge`·`admin-category-edit`·`admin-category-delete`·`admin-category-form`·`admin-category-slug`·`admin-category-name`·`admin-category-sort-order`·`admin-category-admin-only`·`admin-category-submit`·`admin-category-cancel`·`admin-category-{slug|name|sort-order}-error`·`admin-category-error`. 감사 로그 `admin-action-row`·`admin-actions-prev`/`next`·`admin-actions-cap`. 다이얼로그 `admin-confirm-dialog`·`admin-reason-input`·`admin-confirm-submit`·`admin-confirm-cancel`·`admin-confirm-error`.

### 제한 세션(step-up)·로그인 수단 해제·OTP 잠금 (TASK-32)

- **`StepUpNotice`** (`src/components/step-up-notice.tsx`): 제한 세션 안내의 유일한 표시다. 제목 "다시 인증이 필요합니다", 표준 문구
  (`ERROR_MESSAGES.step_up_required`), `/account/#step-up` "다시 인증하기" 링크, 자리마다 다른 한 줄(`children`), `data-step-up-notice` 표식.
  레이아웃 `StepUpBanner`, `ApiErrorAlert`(403 `step_up_required`면 오류 줄 대신 이것을 `testId` 그대로 그린다 — 좋아요·삭제·관리 다이얼로그·해제
  다이얼로그 등 모든 API 오류 자리), 글·댓글 삭제 미리 막기, 글 편집기, 댓글 폼이 쓴다. 새 화면도 step-up 안내는 이것으로 한다.
  - 두 모양(유저 결정): 기본은 제목·표준 문구가 있는 `role="alert"`(배너·API 오류·삭제를 눌렀을 때). `inline`은 화면 안에 처음부터 떠 있는
    조용한 간략형 `role="status"`로, 짧은 한 줄("등록하려면 본인 확인이 필요합니다 · 작성 내용은 임시 저장됩니다", 수정 화면은 "저장하려면")과 같은
    링크만 보인다 — 글 편집기·댓글 폼이 쓴다. 상단 배너가 이미 alert 로 알리므로 같은 내용을 alert 로 거듭 읽히지 않게 한다.
- **작성 컨트롤 미리 막기**: `me.stepUpRequired`(편집기·댓글은 `useSessionUser`의 마지막 사용자)면
  - 글 편집기(작성·수정): 등록·저장 버튼과 이미지 선택을 막고 `editor-step-up` 간략형 안내. 제출 함수도 막아 Enter 제출이 요청하지 않는다. 입력은 그대로 되고
    초안(TASK-29)에 남으므로 다시 인증한 뒤(같은 사용자라 초안이 지워지지 않는다) 화면을 다시 열면 이어서 쓴다.
  - 댓글 폼: 입력은 두고 등록을 막고 `comment-step-up` 간략형 안내(입력은 댓글 초안).
  - 글·댓글 삭제(유저 결정): 버튼은 그대로 두되, 누르면 확인 창("삭제할까요?")보다 먼저 `post-delete-step-up`·`comment-delete-step-up` 안내를
    보이고 요청하지 않는다 — 확인까지 받은 뒤 403 으로 실패하지 않게 한다.
  - 내 계정: 로그인 수단 해제 버튼과 소셜·휴대폰 연결(기존)을 막고 이유를 보인다.
  - 미리 막지 않는 것: 목록의 "글쓰기" 링크(편집기에서 안내하고 초안을 쓸 수 있다), 좋아요, 관리자 작업. 누르면 서버 403이
    `ApiErrorAlert` → `StepUpNotice`로 같은 안내가 된다. 다른 탭에서 다시 인증하면 me 재조회(창 포커스·5분)로 풀린다.
- **로그인 수단 해제** (B1: 사용자 확인 없이 해제하지 않는다): "해제"는 확인 다이얼로그(`ConfirmDialog`, alertdialog)를 연다. 확인해야만
  `DELETE /api/me/identities/{id}`, 취소·Esc는 요청 없음. LOCAL이면 서버가 비밀번호(`local_credentials`)도 지우므로 "저장된 비밀번호도 함께 삭제되어
  되돌릴 수 없습니다" 경고를 덧붙인다. 오류(409 `last_identity` 등)는 다이얼로그 안에 보이고, 성공하면 닫고 상단에 알린다.
- **휴대폰 OTP 잠금** (`PhoneOtpForm`): 서버는 틀린 시도 5번(`app.otp.max-attempts`)에 챌린지를 폐기하지만 응답은 그 뒤에도 같은 401 `invalid_otp`라,
  폼이 지금 챌린지의 `invalid_otp` 수를 센다(`OTP_MAX_ATTEMPTS`=5, 백엔드 기본값과 맞춘다 — 운영에서 바꾸면 함께 바꾼다). 5번째면 인증번호 칸을
  비우고 입력·확인을 막고 "5번 틀려 … 인증번호를 다시 받으세요"(`otp-locked`)를 보이며, 포커스를 "인증번호 다시 받기" 버튼으로 옮긴다(막힌 칸에서
  포커스를 잃지 않게). 새 인증번호를 받으면 초기화한다. 다시 받기 대기는 클라이언트가 미리 계산하지 않고 서버 429 `Retry-After` 카운트다운(기존)을
  따른다(유저 결정: 대기 규칙의 기준은 서버). 그동안 잠금 안내는 남는다. `invalid_otp`가 아닌 실패(503·409 등 — TASK-25#5로 같은
  인증번호를 다시 쓸 수 있다)는 세지 않는다. 폼이 다시 마운트되면(새로고침·화면 이동 등) `challengeId`도 사라져 인증번호 칸이 없어지고, 새로
  받은 챌린지는 서버·클라이언트 모두 0부터 세므로 어긋나지 않는다. 한계: 서버는 틀린 시도를 기록했는데 클라이언트는 네트워크 오류·타임아웃을
  받은 경우(세지 않는다) 클라이언트 횟수가 서버보다 적어, 잠금 안내가 1–2회 늦게 나온다 — 그 사이 실패는 일반 `invalid_otp` 문구로 보인다. 먼저
  나오는 일은 없다.
