# 소셜 로그인 콘솔 설정 (TASK-18 AC#2)

Google, GitHub, Naver, Kakao, Cognito의 콘솔에 콜백 URL을 등록하고 client id·secret을 SSM에 넣는 절차다.
앱 쪽 동작(계정 생성 규칙, 실패 코드)은 [docs/api.md의 소셜 로그인 절](api.md#소셜-로그인-google--github--naver--kakao--cognito-과-계정-연결)에 있다.

> 콘솔 화면과 메뉴 이름은 제공자가 자주 바꾼다. 아래 경로는 2026-10 기준의 안내다. 화면이 다르면 같은 이름의 설정(Redirect URI, Client Secret, 동의 항목)을 찾는다.
> **(확인 필요)**로 표시한 항목은 정책이 바뀌었을 수 있다. 등록하기 전에 그 제공자의 공식 문서로 확인한다.

## 콜백 URL

앱은 요청 Host를 보지 않고 `PUBLIC_BASE_URL`로 redirect-uri를 만든다. 콘솔에는 아래 URL을 **글자 그대로** 등록한다(끝에 `/`를 붙이지 않는다).

```
${PUBLIC_BASE_URL}/api/login/oauth2/code/{google|github|naver|kakao|cognito}
```

| 환경 | `PUBLIC_BASE_URL` | 예 (google) |
|---|---|---|
| 운영 | `terraform -chdir=infra/main output -raw public_base_url` → `https://dxxxxxxxxxxxx.cloudfront.net` (도메인을 쓰면 `https://<domain_name>`) | `https://dxxxxxxxxxxxx.cloudfront.net/api/login/oauth2/code/google` |
| 로컬 | `http://localhost:3000` (`make frontend-dev`가 `/api`를 백엔드로 프록시) | `http://localhost:3000/api/login/oauth2/code/google` |

- 로그인 시작 URL은 `${PUBLIC_BASE_URL}/api/oauth2/authorization/{id}`이다. 화면의 소셜 버튼이 이 주소로 이동한다. 계정에 수단을 연결할 때는 `/api/me/identities/link/{id}`로 시작하지만 콜백은 같다.
- `PUBLIC_BASE_URL`이 바뀌면(도메인 구입 등) **모든 콘솔**의 콜백을 바꾸고 SSM의 `PUBLIC_BASE_URL`도 바꾼 뒤 `make deploy-backend`를 실행한다.
- client id·secret이 없는 제공자는 앱에 등록되지 않는다. 앱은 정상으로 뜨고, 그 버튼을 누르면 `?error=unknown_provider`가 된다. 그래서 제공자를 하나씩 켜도 된다.

## SSM에 넣기

```bash
# deploy/secrets.local.txt (gitignore) 에 채운다
GOOGLE_CLIENT_ID=...      GOOGLE_CLIENT_SECRET=...
GITHUB_CLIENT_ID=...      GITHUB_CLIENT_SECRET=...
NAVER_CLIENT_ID=...       NAVER_CLIENT_SECRET=...
KAKAO_CLIENT_ID=<REST API 키>   KAKAO_CLIENT_SECRET=<보안 → Client Secret 코드>

make put-secrets            # *_SECRET 은 SecureString, *_ID 는 String
make deploy-backend         # 컨테이너가 env 파일을 배포할 때 다시 읽는다(같은 코드면 이미지도 같다)
```

Cognito 값(`COGNITO_CLIENT_ID`·`COGNITO_CLIENT_SECRET`·`COGNITO_ISSUER_URI`·`COGNITO_DOMAIN`)은 `put-secrets.sh`가 Terraform 출력에서 직접 넣으므로 파일에 적지 않는다.
로컬에서는 같은 키를 repo 루트의 env 파일(`example.env` 참고)에 넣는다. 로컬용 앱과 운영용 앱을 따로 만들었다면 id·secret도 각각 다르다.

## Google

1. [Google Cloud Console](https://console.cloud.google.com/)에서 프로젝트를 만들거나 고른다.
2. **Google Auth Platform → Branding**(구 "OAuth 동의 화면"): 앱 이름, 지원 이메일, 개발자 연락처를 넣는다. **Audience**: 외부(External)를 고른다.
   게시 상태가 **Testing**이면 **Test users**에 등록한 Google 계정만 로그인할 수 있다.
3. **Data access**(범위): `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`. 앱은 `openid profile email`만 요청한다. 모두 민감하지 않은 범위다.
4. **Clients → Create client** → 유형 **Web application**:
   - Authorized redirect URIs: 운영 `https://<cf>/api/login/oauth2/code/google`, 로컬 `http://localhost:3000/api/login/oauth2/code/google`(한 클라이언트에 여러 개를 넣을 수 있다).
   - Authorized JavaScript origins는 비워도 된다(서버 코드 흐름).
5. Client ID → `GOOGLE_CLIENT_ID`, Client secret → `GOOGLE_CLIENT_SECRET`.
6. 실제 사용자에게 열려면 **Audience → Publish app**(In production)을 누른다. 민감하지 않은 범위만 쓰면 보통 Google 검수 없이 게시되지만,
   브랜딩(로고 등)을 넣으면 브랜드 확인이 필요할 수 있다 **(확인 필요)**.
   - **cloudfront.net 주의**: 게시하거나 브랜드를 확인하려면 **Authorized domains**에 소유 확인(Search Console)된 도메인이 있어야 할 수 있다. `*.cloudfront.net`은
     내 소유로 확인할 수 없다. Testing 상태(테스트 사용자만)에서는 cloudfront.net 콜백으로도 동작한다 **(확인 필요)**. → [아래 대안](#cloudfrontnet-도메인-위험과-대안)

## GitHub

1. GitHub → **Settings → Developer settings → OAuth Apps → New OAuth App**(조직 소유로 만들려면 Organization settings에서 만든다).
2. Application name, Homepage URL(`PUBLIC_BASE_URL`), **Authorization callback URL**(`.../api/login/oauth2/code/github`).
3. **OAuth App은 callback URL을 하나만 받는다.** 앱이 보내는 `redirect_uri`는 그 URL과 호스트·포트가 같아야 한다. 그래서 **운영용 앱과 로컬용 앱을 따로 만든다**
   (예: `board (prod)` → `https://<cf>/...`, `board (local)` → `http://localhost:3000/...`).
4. **Generate a new client secret** → Client ID → `GITHUB_CLIENT_ID`, secret → `GITHUB_CLIENT_SECRET`. secret은 만들 때 한 번만 보인다.
5. 범위(scope): 앱은 `read:user`를 요청한다. 이메일은 사용자가 공개한 경우에만 받는다(비공개면 계정 email은 비어 있다). 콘솔에서 따로 설정할 것은 없다.
6. "Enable Device Flow"는 끈다(쓰지 않음).

## Naver

1. [Naver Developers](https://developers.naver.com/) → **Application → 애플리케이션 등록**.
2. 애플리케이션 이름, **사용 API: 네이버 로그인**을 고른다.
3. **제공 정보 선택**: 앱은 scope를 보내지 않으므로 이 설정에서 받을 항목이 정해진다. **별명**(또는 **이름**)과 **이메일 주소**를 고른다
   (필수·추가 구분은 사용자 동의 화면에 영향을 준다). 닉네임은 별명 → 이름 순서로 쓰고, 둘 다 없으면 기본값을 쓴다.
4. **로그인 오픈 API 서비스 환경: PC 웹**
   - 서비스 URL: `PUBLIC_BASE_URL`(운영은 `https://<cf>`)
   - **네이버 로그인 Callback URL**: 운영 `https://<cf>/api/login/oauth2/code/naver`와 로컬 `http://localhost:3000/api/login/oauth2/code/naver`.
     여러 개를 넣을 수 있다(개수 제한은 콘솔에 표시된다).
5. 등록 후 **Client ID** → `NAVER_CLIENT_ID`, **Client Secret** → `NAVER_CLIENT_SECRET`. 앱은 토큰 요청에 둘을 파라미터로 보낸다(`client_secret_post`).
6. **개발 중** 상태에서는 **멤버관리 → 테스트 ID**에 등록한 네이버 아이디만 로그인할 수 있다. 일반 사용자에게 열려면 **검수 요청**을 한다.
   검수에는 서비스 화면과 개인정보 처리방침 등이 필요하다 **(확인 필요: 검수 요건)**.

## Kakao

1. [Kakao Developers](https://developers.kakao.com/) → **내 애플리케이션 → 애플리케이션 추가하기**.
2. **앱 키**의 **REST API 키** → `KAKAO_CLIENT_ID`. JavaScript 키나 네이티브 앱 키가 아니다.
3. **플랫폼 → Web → 사이트 도메인**: `https://<cf>`, `http://localhost:3000`.
4. **카카오 로그인 → 활성화 설정 ON**, **Redirect URI**: `https://<cf>/api/login/oauth2/code/kakao`, `http://localhost:3000/api/login/oauth2/code/kakao`.
   콘솔 개편 뒤에는 Redirect URI가 REST API 키 설정 아래에 있을 수 있다 **(확인 필요)**.
5. **카카오 로그인 → 보안 → Client Secret**: 코드를 생성하고 **활성화 상태: 사용함**으로 둔다. 코드 → `KAKAO_CLIENT_SECRET`(`client_secret_post`).
   "사용함"으로 두고 앱에 secret을 넣지 않으면(또는 그 반대면) 토큰 요청이 실패한다.
6. **동의 항목**:
   - **닉네임**(`profile_nickname`): 필수 또는 선택 동의로 설정한다.
   - **카카오계정(이메일)**(`account_email`): 선택 동의로 설정한다. 이 항목은 **비즈 앱**(개인 개발자 비즈 앱 포함)으로 전환해야 쓸 수 있다 **(확인 필요)**.
   - **KOE205 주의**: 앱은 `scope=profile_nickname,account_email`을 요청한다. 두 항목 중 하나라도 동의 항목으로 설정돼 있지 않으면 Kakao가 인가 화면에서
     **KOE205**(설정하지 않은 동의 항목 요청)로 거부한다. 이메일을 설정할 수 없는 동안에는 Kakao 로그인을 켜지 않거나, 코드의 scope를 `profile_nickname`만으로
     줄여야 한다(`KakaoUserInfoMapper.SCOPE`, 별도 변경).
   - 이메일은 선택 동의라 사용자가 거부해도 가입된다(계정 email은 비어 있다). `is_email_valid`·`is_email_verified`가 모두 true일 때만 기록한다.
7. 앱을 공개하기 전에 **앱 권한 신청**과 **비즈니스 인증**이 필요한 항목이 있는지 확인한다 **(확인 필요)**.

## Cognito

콘솔 작업이 거의 없다. Terraform `infra/main/modules/cognito`가 User Pool, App Client(secret 있음, code flow, `openid email profile`), Hosted UI 도메인을 만들고
**콜백 `${public_base_url}/api/login/oauth2/code/cognito`를 App Client에 등록한다.** 앱 설정값은 `put-secrets.sh`가 Terraform 출력에서 넣는다.

| SSM 키 | Terraform 출력 |
|---|---|
| `COGNITO_CLIENT_ID` | `cognito.client_id` |
| `COGNITO_CLIENT_SECRET` | `cognito_client_secret` (sensitive, SecureString) |
| `COGNITO_ISSUER_URI` | `cognito.issuer_uri` (`https://cognito-idp.us-east-1.amazonaws.com/<pool id>`) |
| `COGNITO_DOMAIN` | `cognito.domain_url` (`https://<prefix>.auth.us-east-1.amazoncognito.com`) |

- 로컬에서도 Cognito로 로그인하려면 `prod.tfvars`에 `cognito_extra_callback_urls = ["http://localhost:3000/api/login/oauth2/code/cognito"]`와
  `cognito_extra_logout_urls = ["http://localhost:3000/"]`를 넣고 apply한다. Cognito는 `localhost`만 http를 허용한다. 로컬 env 파일에는 같은 `COGNITO_*` 값을 넣는다.
- 사용자는 Hosted UI에서 가입하고, 이메일 인증 코드를 받은 뒤 로그인한다. Cognito 기본 이메일 발송은 하루 발송량이 적다. 사용자가 많아지면 SES 연동을 검토한다 **(확인 필요: 현재 한도)**.
- 도메인이 바뀌면 `terraform apply`가 콜백을 다시 등록한다(`public_base_url`은 출력에서 자동으로 바뀐다).

## cloudfront.net 도메인 위험과 대안

처음에는 도메인 없이 `https://dxxxx.cloudfront.net`으로 시작한다(decision-9). 기술적으로는 모든 제공자가 https 콜백을 받는다. 다만 다음 위험이 있다.

- **Google**: 앱을 게시하거나 브랜드를 확인하려면 소유를 확인한 **Authorized domain**이 필요할 수 있다. cloudfront.net 하위 도메인은 소유를 확인할 수 없다.
- **Naver·Kakao 검수**: 서비스 URL이 임시 도메인이면 검수에서 반려될 수 있다 **(확인 필요)**.
- CloudFront 배포를 다시 만들면 `dxxxx`가 바뀌고, 모든 콘솔의 콜백을 다시 등록해야 한다.

**대안 — 도메인을 쓴다**:

1. 도메인을 산다(Route 53 Registrar 또는 외부 등록 기관).
2. `prod.tfvars`에 `domain_name = "board.example.com"`(필요하면 `subject_alternative_names`)을 넣는다. Route 53에 zone이 없으면 `create_hosted_zone = true`로 둔다.
   외부 등록 기관이면 apply 뒤 `terraform output dns_name_servers`를 그 등록 기관의 NS로 넣는다.
3. `terraform apply`를 실행하면 `dns_cert` 모듈이 ACM 인증서(us-east-1, DNS 검증), CloudFront alias, A/AAAA 레코드를 만든다. `public_base_url`은 `https://board.example.com`이 되고 Cognito 콜백도 바뀐다.
   NS가 전파되기 전에는 ACM 검증이 끝나지 않아 apply가 기다린다.
4. `make put-secrets`(`PUBLIC_BASE_URL`이 Terraform 출력에서 바뀐다) → `make deploy-backend`.
5. 각 콘솔의 콜백, 서비스 URL, 사이트 도메인을 새 도메인으로 바꾼다. Google은 Authorized domains에 도메인을 추가하고 Search Console에서 소유를 확인한다.

## 확인 체크리스트

제공자마다 운영(`https://<cf>`)과 로컬(`http://localhost:3000`)에서 확인한다.

| | Google | GitHub | Naver | Kakao | Cognito |
|---|---|---|---|---|---|
| 콘솔에 운영 콜백 등록 | [ ] | [ ] (prod 앱) | [ ] | [ ] | Terraform |
| 콘솔에 로컬 콜백 등록 | [ ] | [ ] (local 앱) | [ ] | [ ] | `cognito_extra_callback_urls` |
| 범위·동의 항목 | openid·email·profile | read:user | 제공 정보: 별명·이메일 | profile_nickname·account_email (KOE205) | openid·email·profile |
| 테스트 사용자 / 검수 | Test users → Publish | – | 테스트 ID → 검수 요청 | 비즈 앱·동의 항목 | Hosted UI 가입 |
| SSM에 id·secret | [ ] | [ ] | [ ] | [ ] | put-secrets 자동 |

제공자마다 다음을 확인한다.

1. 로그인 화면에서 "◯◯로 로그인" → 제공자 동의 화면 → `/auth/callback/?status=ok` → 홈 헤더에 닉네임이 보인다.
2. `/account/`의 로그인 수단 목록에 그 제공자가 보인다.
3. 다른 계정(LOCAL 등)으로 로그인한 상태에서 `/account/` → **연결** → `?linked=<id>`. 이미 다른 계정에 붙은 수단이면 `identity_in_use` 안내가 뜬다.
4. 동의 화면에서 **취소**하면 `?error=access_denied`이고 "로그인을 취소했습니다" 계열 안내가 뜬다.
5. 실패하면 백엔드 로그(`aws logs tail /board/prod --log-stream-name-prefix api/`)와 아래 증상표를 본다.

| 증상 | 원인 |
|---|---|
| 제공자 화면에 `redirect_uri_mismatch` / "Redirect URI 불일치" | 콘솔 콜백과 `PUBLIC_BASE_URL`이 글자 단위로 다르다(http/https, 끝 `/`, 도메인) |
| `?error=unknown_provider` | 그 제공자의 `*_CLIENT_ID`가 SSM에 없거나 배포 뒤 반영되지 않았다(`make deploy-backend`) |
| `?error=invalid_token_response` (Naver) | Client Secret이 틀렸거나 앱이 "개발 중"인데 테스트 ID가 아니다 |
| Kakao `KOE205` | 동의 항목에 `profile_nickname`이나 `account_email`이 설정되지 않았다 |
| Kakao `KOE010`·`invalid_client` | Client Secret을 "사용함"으로 두고 값을 넣지 않았거나 값이 틀렸다 |
| `?error=authorization_request_not_found` | 로그인을 시작한 브라우저와 콜백 브라우저가 다르거나, 세션 쿠키가 막혔다(`COOKIE_SECURE=true`인데 http) |
