# cognito 모듈

Cognito User Pool 을 OIDC 제공자 하나로 쓴다(decision-6). 앱(Spring)은 registrationId `cognito` 로 Hosted UI 를 거쳐
authorization code flow 로 로그인하고, id_token 을 issuer JWKS 로 검증한다.

만드는 것:

- `aws_cognito_user_pool`: 이메일이 사용자 이름(대소문자 무시), 가입 시 이메일 검증, 비밀번호 12자 이상(대·소문자·숫자), 복구는 검증된 이메일, 삭제 보호(기본 켜짐)
- `aws_cognito_user_pool_client`: secret 발급(confidential client), `code` flow, scope `openid email profile`, 제공자는 `COGNITO` 만
- `aws_cognito_user_pool_domain`: Hosted UI `https://<domain_prefix>.auth.<region>.amazoncognito.com` (`managed_login_version` 1 = 클래식, 2 = managed login + 기본 branding)

## 사용

```hcl
module "cognito" {
  source = "../../modules/cognito"

  name            = "board-prod"
  public_base_url = "https://d111.cloudfront.net" # 앱의 PUBLIC_BASE_URL
  domain_prefix   = "board-prod-login"
  # callback_urls = [...] # 기본: ["<public_base_url>/api/login/oauth2/code/cognito"]
  # deletion_protection = false # 실습 환경을 destroy 하려면
}
```

## 출력 → 앱 env

| 출력 | 앱 env |
|---|---|
| `issuer_uri` | `COGNITO_ISSUER_URI` |
| `client_id` | `COGNITO_CLIENT_ID` |
| `client_secret` (sensitive) | `COGNITO_CLIENT_SECRET` (SSM SecureString) |
| `domain_url` | `COGNITO_DOMAIN` |

## 테스트

`tests/*.tftest.hcl` 은 `mock_provider "aws"` 로 AWS 자격 증명 없이 돈다. `make test`(scripts/test.sh)가
`terraform init -backend=false` 후 `terraform test` 를 실행한다.

lock 파일: 재사용 모듈이라 provider 는 `~> 6.0` 범위만 두고 `.terraform.lock.hcl` 은 커밋하지 않는다(.gitignore).
실제 버전 고정은 이 모듈을 쓰는 루트 스택(`infra/main/envs/*`)의 lock 파일이 한다.
