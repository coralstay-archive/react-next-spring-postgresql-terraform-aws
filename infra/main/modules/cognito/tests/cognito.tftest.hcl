# TASK-6#1 cognito 모듈 테스트. mock_provider 라 AWS 자격 증명·네트워크 호출 없이 돈다(provider 스키마만 init 으로 받는다).
# 실행: scripts/test.sh (terraform init -backend=false && terraform test)

mock_provider "aws" {}

# mock 은 계산 값(id 등)을 임의 문자열로 채운다. issuer·도메인 URL 을 검사하려고 pool id 와 리전을 고정한다.
override_resource {
  target = aws_cognito_user_pool.this
  values = {
    id = "us-east-1_TEST"
  }
}

override_data {
  target = data.aws_region.current
  values = {
    region = "us-east-1"
  }
}

variables {
  name            = "board-test"
  public_base_url = "https://d111.cloudfront.net"
  domain_prefix   = "board-test-login"
}

# TASK-6#1 User Pool: 이메일이 사용자 이름, 이메일 자동 검증, 비밀번호 정책, 삭제 보호 기본 켜짐
run "user_pool_defaults" {
  command = apply

  assert {
    condition     = aws_cognito_user_pool.this.name == "board-test"
    error_message = "User Pool 이름은 var.name"
  }
  assert {
    condition     = toset(aws_cognito_user_pool.this.username_attributes) == toset(["email"])
    error_message = "이메일로 로그인(username_attributes = [email])"
  }
  assert {
    condition     = toset(aws_cognito_user_pool.this.auto_verified_attributes) == toset(["email"])
    error_message = "이메일은 가입 시 코드로 검증"
  }
  assert {
    condition     = aws_cognito_user_pool.this.deletion_protection == "ACTIVE"
    error_message = "삭제 보호는 기본으로 켠다"
  }
  assert {
    condition = (
      aws_cognito_user_pool.this.password_policy[0].minimum_length >= 12
      && aws_cognito_user_pool.this.password_policy[0].require_lowercase
      && aws_cognito_user_pool.this.password_policy[0].require_uppercase
      && aws_cognito_user_pool.this.password_policy[0].require_numbers
    )
    error_message = "비밀번호 정책: 12자 이상, 대·소문자·숫자"
  }
  assert {
    condition     = one([for s in aws_cognito_user_pool.this.schema : s if s.name == "email"]).required
    error_message = "email 속성은 필수"
  }
}

# TASK-6#1 App Client: secret 발급, authorization code flow, openid email profile, 콜백은 public_base_url 에서 만든다
run "app_client" {
  command = apply

  assert {
    condition     = aws_cognito_user_pool_client.this.generate_secret == true
    error_message = "서버 측 클라이언트라 secret 을 발급한다"
  }
  assert {
    condition     = toset(aws_cognito_user_pool_client.this.allowed_oauth_flows) == toset(["code"])
    error_message = "authorization code flow 만 허용"
  }
  assert {
    condition     = aws_cognito_user_pool_client.this.allowed_oauth_flows_user_pool_client == true
    error_message = "OAuth 흐름을 켜야 Hosted UI 를 쓸 수 있다"
  }
  assert {
    condition     = toset(aws_cognito_user_pool_client.this.allowed_oauth_scopes) == toset(["openid", "email", "profile"])
    error_message = "scope 는 openid email profile"
  }
  assert {
    condition     = toset(aws_cognito_user_pool_client.this.supported_identity_providers) == toset(["COGNITO"])
    error_message = "User Pool 자체 계정만 (소셜은 앱이 직접 붙인다, decision-6)"
  }
  assert {
    condition     = toset(aws_cognito_user_pool_client.this.callback_urls) == toset(["https://d111.cloudfront.net/api/login/oauth2/code/cognito"])
    error_message = "콜백 기본값은 {public_base_url}/api/login/oauth2/code/cognito"
  }
  assert {
    condition     = toset(aws_cognito_user_pool_client.this.logout_urls) == toset(["https://d111.cloudfront.net/"])
    error_message = "로그아웃 기본값은 {public_base_url}/"
  }
  assert {
    condition     = aws_cognito_user_pool_client.this.user_pool_id == "us-east-1_TEST"
    error_message = "클라이언트는 이 모듈의 User Pool 에 붙는다"
  }
}

# TASK-6#1 Hosted UI 도메인과 출력: issuer URI, client id/secret(sensitive), 도메인 URL
run "domain_and_outputs" {
  command = apply

  assert {
    condition     = aws_cognito_user_pool_domain.this.domain == "board-test-login"
    error_message = "도메인 prefix 는 var.domain_prefix"
  }
  assert {
    condition     = aws_cognito_user_pool_domain.this.managed_login_version == 1
    error_message = "기본은 클래식 Hosted UI(managed_login_version = 1)"
  }
  assert {
    condition     = length(aws_cognito_managed_login_branding.this) == 0
    error_message = "클래식 Hosted UI 는 branding 리소스가 필요 없다"
  }
  assert {
    condition     = output.user_pool_id == "us-east-1_TEST"
    error_message = "user_pool_id 출력"
  }
  assert {
    condition     = output.issuer_uri == "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_TEST"
    error_message = "issuer_uri = https://cognito-idp.<region>.amazonaws.com/<pool id> (COGNITO_ISSUER_URI)"
  }
  assert {
    condition     = output.domain_url == "https://board-test-login.auth.us-east-1.amazoncognito.com"
    error_message = "domain_url = https://<prefix>.auth.<region>.amazoncognito.com (COGNITO_DOMAIN)"
  }
  assert {
    condition     = output.client_id == aws_cognito_user_pool_client.this.id
    error_message = "client_id 출력"
  }
  assert {
    condition     = output.client_secret == aws_cognito_user_pool_client.this.client_secret && issensitive(output.client_secret)
    error_message = "client_secret 출력은 sensitive"
  }
  assert {
    condition     = output.callback_urls == tolist(["https://d111.cloudfront.net/api/login/oauth2/code/cognito"])
    error_message = "callback_urls 출력"
  }
}

# TASK-6#1 public_base_url 끝의 / 는 떼고 콜백을 만든다
run "trailing_slash_trimmed" {
  command = plan

  variables {
    public_base_url = "https://board.example.com/"
  }

  assert {
    condition     = toset(aws_cognito_user_pool_client.this.callback_urls) == toset(["https://board.example.com/api/login/oauth2/code/cognito"])
    error_message = "끝 / 를 떼고 붙인다"
  }
}

# TASK-6#1 콜백·로그아웃 URL 을 직접 주면 그대로 쓴다(로컬 개발 주소 추가 등)
run "explicit_urls" {
  command = plan

  variables {
    callback_urls = [
      "https://d111.cloudfront.net/api/login/oauth2/code/cognito",
      "http://localhost:3000/api/login/oauth2/code/cognito",
    ]
    logout_urls = ["http://localhost:3000/"]
  }

  assert {
    condition = toset(aws_cognito_user_pool_client.this.callback_urls) == toset([
      "https://d111.cloudfront.net/api/login/oauth2/code/cognito",
      "http://localhost:3000/api/login/oauth2/code/cognito",
    ])
    error_message = "callback_urls 변수를 그대로 쓴다"
  }
  assert {
    condition     = toset(aws_cognito_user_pool_client.this.logout_urls) == toset(["http://localhost:3000/"])
    error_message = "logout_urls 변수를 그대로 쓴다"
  }
}

# TASK-6#1 로컬 개발은 http://localhost 를 허용한다(Cognito 도 localhost 만 http 허용)
run "localhost_allowed" {
  command = plan

  variables {
    public_base_url = "http://localhost:3000"
  }

  assert {
    condition     = toset(aws_cognito_user_pool_client.this.callback_urls) == toset(["http://localhost:3000/api/login/oauth2/code/cognito"])
    error_message = "localhost 는 http 허용"
  }
}

# TASK-6#1 삭제 보호는 끌 수 있다(실습 환경 destroy 용)
run "deletion_protection_off" {
  command = plan

  variables {
    deletion_protection = false
  }

  assert {
    condition     = aws_cognito_user_pool.this.deletion_protection == "INACTIVE"
    error_message = "deletion_protection = false 면 INACTIVE"
  }
}

# TASK-6#1 managed login(v2)을 고르면 기본 branding 스타일을 같이 만든다(없으면 로그인 페이지가 뜨지 않는다)
run "managed_login_v2" {
  command = apply

  variables {
    managed_login_version = 2
  }

  assert {
    condition     = aws_cognito_user_pool_domain.this.managed_login_version == 2
    error_message = "managed_login_version = 2"
  }
  assert {
    condition     = length(aws_cognito_managed_login_branding.this) == 1 && aws_cognito_managed_login_branding.this[0].use_cognito_provided_values
    error_message = "v2 는 Cognito 기본값 branding 을 만든다"
  }
}

# TASK-6#1 Cognito 는 https(또는 http://localhost) 콜백만 받는다
run "rejects_plain_http" {
  command = plan

  variables {
    public_base_url = "http://board.example.com"
  }

  expect_failures = [var.public_base_url]
}

# TASK-6#1 도메인 prefix 에는 aws·amazon·cognito 를 넣을 수 없다(AWS 예약어)
run "rejects_reserved_domain_prefix" {
  command = plan

  variables {
    domain_prefix = "my-cognito-login"
  }

  expect_failures = [var.domain_prefix]
}
