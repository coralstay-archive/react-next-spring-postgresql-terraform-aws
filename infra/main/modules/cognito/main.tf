# Cognito User Pool 을 OIDC 제공자 하나로 쓴다(decision-6). 앱은 registrationId "cognito" 로 Hosted UI 를 거쳐 로그인한다.

data "aws_region" "current" {}

locals {
  base_url      = trimsuffix(var.public_base_url, "/")
  callback_urls = coalesce(var.callback_urls, ["${local.base_url}/api/login/oauth2/code/cognito"])
  logout_urls   = coalesce(var.logout_urls, ["${local.base_url}/"])
  region        = data.aws_region.current.region
}

resource "aws_cognito_user_pool" "this" {
  name = var.name

  # 이메일이 곧 사용자 이름. 가입 시 이메일 코드로 검증한다.
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  username_configuration {
    case_sensitive = false
  }

  user_attribute_update_settings {
    attributes_require_verification_before_update = ["email"]
  }

  schema {
    name                = "email"
    attribute_data_type = "String"
    required            = true
    mutable             = true

    string_attribute_constraints {
      min_length = 3
      max_length = 320
    }
  }

  password_policy {
    minimum_length                   = var.password_minimum_length
    require_lowercase                = true
    require_uppercase                = true
    require_numbers                  = true
    require_symbols                  = false
    temporary_password_validity_days = 7
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  admin_create_user_config {
    allow_admin_create_user_only = false
  }

  # 메일은 Cognito 기본 발신(하루 발송량 제한 있음). 운영 규모가 커지면 SES 로 바꾼다.
  email_configuration {
    email_sending_account = "COGNITO_DEFAULT"
  }

  mfa_configuration   = "OFF"
  deletion_protection = var.deletion_protection ? "ACTIVE" : "INACTIVE"

  tags = var.tags
}

resource "aws_cognito_user_pool_client" "this" {
  name         = "${var.name}-web"
  user_pool_id = aws_cognito_user_pool.this.id

  # 백엔드(Spring)가 토큰 교환을 하는 confidential client → secret 발급(client_secret_basic)
  generate_secret = true

  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = local.callback_urls
  logout_urls                          = local.logout_urls

  # Hosted UI 만 쓰므로 직접 인증 API 흐름은 최소로 둔다
  explicit_auth_flows = ["ALLOW_REFRESH_TOKEN_AUTH", "ALLOW_USER_SRP_AUTH"]

  prevent_user_existence_errors = "ENABLED"
  enable_token_revocation       = true

  # 앱은 id_token 으로 신원만 확인하고 자체 세션·JWT 를 쓴다. 토큰 수명은 짧게 둔다.
  id_token_validity      = 60
  access_token_validity  = 60
  refresh_token_validity = 1
  token_validity_units {
    id_token      = "minutes"
    access_token  = "minutes"
    refresh_token = "days"
  }
}

resource "aws_cognito_user_pool_domain" "this" {
  domain                = var.domain_prefix
  user_pool_id          = aws_cognito_user_pool.this.id
  managed_login_version = var.managed_login_version
}

# managed login(v2)은 branding 스타일이 있어야 로그인 페이지가 뜬다. Cognito 기본값을 쓴다.
resource "aws_cognito_managed_login_branding" "this" {
  count = var.managed_login_version == 2 ? 1 : 0

  user_pool_id                = aws_cognito_user_pool.this.id
  client_id                   = aws_cognito_user_pool_client.this.id
  use_cognito_provided_values = true
}
