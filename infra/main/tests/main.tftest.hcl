# TASK-16#6 루트 스택(infra/main) 연결 테스트. 모듈 사이 배선·토글(dns_cert, ci_oidc)·출력을 mock_provider 로 오프라인 검사한다.
# 실제 plan 은 AWS 자격 증명이 있을 때 `terraform plan -var-file=envs/prod.tfvars` 로 한다(TASK-18).

mock_provider "aws" {
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/mock"
    }
  }
  mock_resource "aws_cloudfront_function" {
    defaults = {
      arn = "arn:aws:cloudfront::123456789012:function/mock"
    }
  }
  mock_resource "aws_iam_openid_connect_provider" {
    defaults = {
      arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    }
  }
}

mock_provider "aws" {
  alias = "us_east_1"

  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:us-east-1:123456789012:certificate/test"
    }
  }
  mock_resource "aws_acm_certificate_validation" {
    defaults = {
      certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/test"
    }
  }
}

override_resource {
  target = module.frontend_cdn.aws_cloudfront_distribution.this
  values = {
    id             = "E2TEST"
    arn            = "arn:aws:cloudfront::123456789012:distribution/E2TEST"
    domain_name    = "d111.cloudfront.net"
    hosted_zone_id = "Z2FDTNDATAQYW2"
  }
}

override_resource {
  target = module.frontend_cdn.aws_s3_bucket.web
  values = {
    id  = "board-test-web"
    arn = "arn:aws:s3:::board-test-web"
  }
}

override_resource {
  target = module.backend_host.aws_eip.this
  values = {
    public_dns = "ec2-3-1-2-3.compute-1.amazonaws.com"
  }
}

override_resource {
  target = module.backend_host.aws_instance.this
  values = {
    id  = "i-0123456789abcdef0"
    arn = "arn:aws:ec2:us-east-1:123456789012:instance/i-0123456789abcdef0"
  }
}

override_resource {
  target = module.uploads.aws_s3_bucket.this
  values = {
    id                          = "board-test-uploads"
    arn                         = "arn:aws:s3:::board-test-uploads"
    bucket_regional_domain_name = "board-test-uploads.s3.us-east-1.amazonaws.com"
  }
}

override_data {
  target = module.backend_host.data.aws_ssm_parameter.ami
  values = {
    value          = "ami-0123456789abcdef0"
    insecure_value = "ami-0123456789abcdef0"
  }
}

override_data {
  target = module.backend_host.data.aws_region.current
  values = {
    region = "us-east-1"
  }
}

override_data {
  target = module.cognito.data.aws_region.current
  values = {
    region = "us-east-1"
  }
}

variables {
  name                  = "board-test"
  availability_zone     = "us-east-1a"
  web_bucket_name       = "board-test-web"
  uploads_bucket_name   = "board-test-uploads"
  cognito_domain_prefix = "board-test-login"
}

# TASK-16#6 기본(도메인 없음, GitHub 리포 미지정): cloudfront.net 주소, dns_cert·ci_oidc 꺼짐
run "defaults_cloudfront_domain" {
  command = apply

  assert {
    condition     = output.public_base_url == "https://d111.cloudfront.net"
    error_message = "도메인이 없으면 PUBLIC_BASE_URL = https://<cloudfront 도메인>(decision-9)"
  }
  assert {
    condition     = length(module.dns_cert) == 0 && length(module.ci_oidc) == 0
    error_message = "domain_name·github_repository 가 비면 dns_cert·ci_oidc 를 만들지 않는다"
  }
  assert {
    condition     = output.ci_role_arn == null
    error_message = "ci_oidc 가 꺼지면 ci_role_arn 은 null"
  }
  assert {
    condition     = module.frontend_cdn.viewer_certificate_arn == null && length(module.frontend_cdn.aliases) == 0
    error_message = "기본 인증서"
  }
}

# TASK-16#6 모듈 배선: /api/* origin = EIP DNS, /uploads/* origin = uploads 버킷, uploads 버킷 정책 = 이 배포, 앱 role 에 uploads 정책
run "wiring" {
  command = apply

  assert {
    condition     = module.frontend_cdn.origin_domains["api"] == "ec2-3-1-2-3.compute-1.amazonaws.com"
    error_message = "/api/* origin 은 backend_host EIP public DNS"
  }
  assert {
    condition     = module.frontend_cdn.origin_domains["uploads"] == "board-test-uploads.s3.us-east-1.amazonaws.com"
    error_message = "/uploads/* origin 은 uploads 버킷"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(module.uploads.bucket_policy_json).Statement :
      try(s.Condition.StringEquals["AWS:SourceArn"], "") == "arn:aws:cloudfront::123456789012:distribution/E2TEST"
    ])
    error_message = "uploads 버킷은 이 배포(OAC)만 읽기"
  }
  assert {
    condition     = toset(module.uploads.cors_allowed_origins) == toset(["https://d111.cloudfront.net"])
    error_message = "presigned PUT CORS origin = PUBLIC_BASE_URL"
  }
  assert {
    condition     = module.backend_host.extra_policy_jsons == [module.uploads.app_policy_json]
    error_message = "앱 role 에 uploads app_policy_json"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(module.backend_host.app_policy_json).Statement :
      s.Resource == module.frontend_cdn.ecr_repository_arn
    ])
    error_message = "앱 role 의 ECR pull 은 frontend_cdn 의 리포"
  }
  assert {
    condition     = module.network.availability_zone == "us-east-1a" && module.backend_host.subnet_id == module.network.subnet_id
    error_message = "EC2 는 network 의 public subnet"
  }
  assert {
    condition     = toset(module.cognito.callback_urls) == toset(["https://d111.cloudfront.net/api/login/oauth2/code/cognito"])
    error_message = "Cognito 콜백은 PUBLIC_BASE_URL 에서"
  }
}

# TASK-16#6 출력: 배포 스크립트(TASK-17)·앱 env 에 필요한 값
run "outputs" {
  command = apply

  assert {
    condition     = output.cloudfront_domain == "d111.cloudfront.net" && output.distribution_id == "E2TEST"
    error_message = "cloudfront_domain, distribution_id"
  }
  assert {
    condition     = output.instance_id == "i-0123456789abcdef0" && output.eip_public_dns == "ec2-3-1-2-3.compute-1.amazonaws.com"
    error_message = "instance_id, eip_public_dns"
  }
  assert {
    condition     = output.web_bucket == "board-test-web" && output.uploads_bucket == "board-test-uploads"
    error_message = "web_bucket, uploads_bucket"
  }
  assert {
    condition     = output.cognito.issuer_uri == module.cognito.issuer_uri && output.cognito.client_id == module.cognito.client_id && output.cognito.domain_url == module.cognito.domain_url
    error_message = "cognito 출력"
  }
  assert {
    condition     = issensitive(output.cognito_client_secret)
    error_message = "client_secret 은 sensitive"
  }
  assert {
    condition     = output.log_group == "/board/prod" && output.ssm_parameter_path == "/board/prod"
    error_message = "log_group, ssm_parameter_path"
  }
  assert {
    condition = (
      output.app_env.PUBLIC_BASE_URL == "https://d111.cloudfront.net"
      && output.app_env.COOKIE_SECURE == "true"
      && output.app_env.CLIENT_IP_HEADER == "CloudFront-Viewer-Address"
      && output.app_env.UPLOADS_BUCKET == "board-test-uploads"
      && output.app_env.UPLOADS_REGION == "us-east-1"
      && output.app_env.UPLOADS_PUBLIC_BASE_PATH == "/uploads/"
      && output.app_env.SMS_AWS_REGION == "us-east-1"
    )
    error_message = "app_env: 비밀이 아닌 앱 설정(UPLOADS_REGION 은 us-east-1 을 명시, TASK-11)"
  }
  assert {
    condition     = output.app_env.SMS_PROVIDER == "aws"
    error_message = "TASK-21#4 app_env.SMS_PROVIDER 는 aws (운영 모드는 aws 외에 기동하지 않는다. secrets 파일 한 줄에 기대지 않는다)"
  }
  assert {
    condition     = !contains(keys(output.app_env), "COGNITO_CLIENT_SECRET")
    error_message = "app_env 에 시크릿을 넣지 않는다(SSM, TASK-17)"
  }
}

# TASK-16#6 domain_name 을 주면 dns_cert(us-east-1 ACM) 를 켜고 aliases·인증서·PUBLIC_BASE_URL 이 도메인으로 바뀐다
run "custom_domain" {
  command = apply

  variables {
    domain_name = "board.example.com"
  }

  override_data {
    target = module.dns_cert[0].data.aws_route53_zone.existing[0]
    values = {
      zone_id = "Z0EXISTING"
    }
  }

  override_resource {
    target = module.dns_cert[0].aws_acm_certificate.this
    values = {
      arn = "arn:aws:acm:us-east-1:123456789012:certificate/test"
      domain_validation_options = [{
        domain_name           = "board.example.com"
        resource_record_name  = "_abc.board.example.com."
        resource_record_type  = "CNAME"
        resource_record_value = "_xyz.acm-validations.aws."
      }]
    }
  }

  assert {
    condition     = length(module.dns_cert) == 1
    error_message = "dns_cert 켜짐"
  }
  assert {
    condition     = toset(module.frontend_cdn.aliases) == toset(["board.example.com"])
    error_message = "CloudFront aliases"
  }
  assert {
    condition     = module.frontend_cdn.viewer_certificate_arn == "arn:aws:acm:us-east-1:123456789012:certificate/test"
    error_message = "ACM 인증서"
  }
  assert {
    condition     = output.public_base_url == "https://board.example.com"
    error_message = "PUBLIC_BASE_URL 은 사용자 도메인"
  }
  assert {
    condition     = toset(module.cognito.callback_urls) == toset(["https://board.example.com/api/login/oauth2/code/cognito"])
    error_message = "Cognito 콜백도 사용자 도메인"
  }
}

# TASK-16#6 github_repository 를 주면 ci_oidc 를 켜고 배포 대상(ECR·web·배포·인스턴스)을 넘긴다
run "ci_enabled" {
  command = apply

  variables {
    github_repository = "octo/board"
  }

  assert {
    condition     = length(module.ci_oidc) == 1 && output.ci_role_arn == module.ci_oidc[0].role_arn
    error_message = "ci_oidc 켜짐 + ci_role_arn 출력"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(module.ci_oidc[0].policy_json).Statement :
      s.Action == ["ssm:SendCommand"] && contains(s.Resource, "arn:aws:ec2:us-east-1:123456789012:instance/i-0123456789abcdef0")
    ])
    error_message = "SendCommand 대상은 backend_host 인스턴스"
  }
}

# TASK-16#6 로컬 개발 콜백을 Cognito 에 추가할 수 있다(선택)
run "cognito_local_callback" {
  command = apply

  variables {
    cognito_extra_callback_urls = ["http://localhost:3000/api/login/oauth2/code/cognito"]
    cognito_extra_logout_urls   = ["http://localhost:3000/"]
  }

  assert {
    condition = toset(module.cognito.callback_urls) == toset([
      "https://d111.cloudfront.net/api/login/oauth2/code/cognito",
      "http://localhost:3000/api/login/oauth2/code/cognito",
    ])
    error_message = "운영 콜백 + 로컬 콜백"
  }
  assert {
    condition     = toset(module.cognito.logout_urls) == toset(["https://d111.cloudfront.net/", "http://localhost:3000/"])
    error_message = "운영 + 로컬 로그아웃 URL"
  }
}
