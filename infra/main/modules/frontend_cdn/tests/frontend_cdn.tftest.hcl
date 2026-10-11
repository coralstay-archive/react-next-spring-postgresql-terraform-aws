# TASK-16#3 frontend_cdn 모듈 테스트: ECR + web S3(OAC) + CloudFront(기본=S3+CF Function, /api/*=EC2, /uploads/*=uploads S3).
# mock_provider(오프라인). managed cache policy ID 는 AWS 문서의 고정 값이다.

mock_provider "aws" {
  mock_resource "aws_cloudfront_distribution" {
    defaults = {
      arn = "arn:aws:cloudfront::123456789012:distribution/E2TEST"
    }
  }
  mock_resource "aws_cloudfront_function" {
    defaults = {
      arn = "arn:aws:cloudfront::123456789012:function/board-test-index-rewrite"
    }
  }
}

override_resource {
  target = aws_s3_bucket.web
  values = {
    id                          = "board-test-web"
    arn                         = "arn:aws:s3:::board-test-web"
    bucket_regional_domain_name = "board-test-web.s3.us-east-1.amazonaws.com"
  }
}

variables {
  name                                = "board-test"
  web_bucket_name                     = "board-test-web"
  ecr_repository_name                 = "board-test-api"
  api_origin_domain                   = "ec2-3-1-2-3.compute-1.amazonaws.com"
  uploads_bucket_regional_domain_name = "board-test-uploads.s3.us-east-1.amazonaws.com"
}

# TASK-16#3 ECR: push 시 이미지 스캔, 최근 20개만 보관
run "ecr" {
  command = apply

  assert {
    condition     = aws_ecr_repository.api.name == "board-test-api" && one(aws_ecr_repository.api.image_scanning_configuration).scan_on_push
    error_message = "리포 이름·scan on push"
  }
  assert {
    condition     = jsondecode(aws_ecr_lifecycle_policy.api.policy).rules[0].selection.countType == "imageCountMoreThan" && jsondecode(aws_ecr_lifecycle_policy.api.policy).rules[0].selection.countNumber == 20
    error_message = "최근 20개 초과분 만료"
  }
}

# TASK-16#3 web 버킷: 퍼블릭 차단, ACL 비활성, SSE, CloudFront(OAC) 배포만 읽기, HTTPS 강제
run "web_bucket" {
  command = apply

  assert {
    condition = (
      aws_s3_bucket_public_access_block.web.block_public_acls && aws_s3_bucket_public_access_block.web.block_public_policy
      && aws_s3_bucket_public_access_block.web.ignore_public_acls && aws_s3_bucket_public_access_block.web.restrict_public_buckets
    )
    error_message = "퍼블릭 차단 4종"
  }
  assert {
    condition     = one(aws_s3_bucket_ownership_controls.web.rule).object_ownership == "BucketOwnerEnforced"
    error_message = "ACL 비활성"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_s3_bucket_policy.web.policy).Statement :
      s.Effect == "Allow" && s.Principal.Service == "cloudfront.amazonaws.com"
      && contains(s.Action, "s3:GetObject") && s.Condition.StringEquals["AWS:SourceArn"] == "arn:aws:cloudfront::123456789012:distribution/E2TEST"
    ])
    error_message = "이 배포(OAC)만 GetObject"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_s3_bucket_policy.web.policy).Statement :
      s.Effect == "Allow" && contains(s.Action, "s3:ListBucket") && s.Resource == "arn:aws:s3:::board-test-web"
    ])
    error_message = "없는 키는 403 이 아니라 404 가 되도록 ListBucket 허용"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_s3_bucket_policy.web.policy).Statement :
      s.Effect == "Deny" && s.Condition.Bool["aws:SecureTransport"] == "false"
    ])
    error_message = "HTTPS 강제"
  }
  assert {
    condition     = aws_cloudfront_origin_access_control.s3.origin_access_control_origin_type == "s3" && aws_cloudfront_origin_access_control.s3.signing_behavior == "always" && aws_cloudfront_origin_access_control.s3.signing_protocol == "sigv4"
    error_message = "OAC: s3, always, sigv4"
  }
}

# TASK-16#3 기본 동작: web 버킷 + CachingOptimized + viewer-request CF Function(/x/ → /x/index.html), HTTPS 리다이렉트
run "default_behavior" {
  command = apply

  assert {
    condition     = one(aws_cloudfront_distribution.this.default_cache_behavior).target_origin_id == "web"
    error_message = "기본은 web 버킷"
  }
  assert {
    condition     = one(aws_cloudfront_distribution.this.default_cache_behavior).cache_policy_id == "658327ea-f89d-4fab-a63d-7e88639e58f6"
    error_message = "Managed-CachingOptimized"
  }
  assert {
    condition     = one(aws_cloudfront_distribution.this.default_cache_behavior).viewer_protocol_policy == "redirect-to-https"
    error_message = "HTTP → HTTPS 리다이렉트"
  }
  assert {
    condition = anytrue([
      for f in one(aws_cloudfront_distribution.this.default_cache_behavior).function_association :
      f.event_type == "viewer-request" && f.function_arn == aws_cloudfront_function.index_rewrite.arn
    ])
    error_message = "viewer-request 에 index 리라이트 함수"
  }
  assert {
    condition     = aws_cloudfront_function.index_rewrite.runtime == "cloudfront-js-2.0" && aws_cloudfront_function.index_rewrite.publish
    error_message = "cloudfront-js-2.0, publish"
  }
  assert {
    condition     = strcontains(aws_cloudfront_function.index_rewrite.code, "index.html")
    error_message = "함수 코드는 index.html 을 붙인다"
  }
  assert {
    condition     = one([for o in aws_cloudfront_distribution.this.origin : o.origin_access_control_id if o.origin_id == "web"]) == aws_cloudfront_origin_access_control.s3.id
    error_message = "web origin 은 OAC"
  }
  assert {
    condition     = one([for o in aws_cloudfront_distribution.this.origin : o.domain_name if o.origin_id == "web"]) == "board-test-web.s3.us-east-1.amazonaws.com"
    error_message = "web origin 은 버킷 regional 도메인"
  }
}

# TASK-16#3 /api/*: EC2(EIP DNS) HTTP 8080, 캐시 끔, 모든 메서드, 쿠키·쿼리·모든 헤더(Authorization 포함) + CloudFront-Viewer-Address, 함수 없음
run "api_behavior" {
  command = apply

  assert {
    condition     = one([for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.target_origin_id if b.path_pattern == "/api/*"]) == "api"
    error_message = "/api/* → api origin"
  }
  assert {
    condition     = one([for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.cache_policy_id if b.path_pattern == "/api/*"]) == "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
    error_message = "Managed-CachingDisabled"
  }
  assert {
    condition = toset(one([for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.allowed_methods if b.path_pattern == "/api/*"])) == toset([
      "GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE",
    ])
    error_message = "모든 메서드"
  }
  assert {
    condition     = one([for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.origin_request_policy_id if b.path_pattern == "/api/*"]) == aws_cloudfront_origin_request_policy.api.id
    error_message = "API 전용 origin request policy"
  }
  assert {
    condition     = length(one([for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.function_association if b.path_pattern == "/api/*"])) == 0
    error_message = "/api/* 에는 index 리라이트(트레일링 슬래시 처리)를 걸지 않는다"
  }
  assert {
    condition     = one([for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.viewer_protocol_policy if b.path_pattern == "/api/*"]) == "https-only"
    error_message = "API 는 HTTPS 만(POST 를 리다이렉트하지 않는다)"
  }
  assert {
    condition = (
      one(aws_cloudfront_origin_request_policy.api.headers_config).header_behavior == "allViewerAndWhitelistCloudFront"
      && contains(one(one(aws_cloudfront_origin_request_policy.api.headers_config).headers).items, "CloudFront-Viewer-Address")
    )
    error_message = "모든 viewer 헤더(Authorization 포함) + CloudFront-Viewer-Address(CLIENT_IP_HEADER, TASK-7)"
  }
  assert {
    condition     = one(aws_cloudfront_origin_request_policy.api.cookies_config).cookie_behavior == "all" && one(aws_cloudfront_origin_request_policy.api.query_strings_config).query_string_behavior == "all"
    error_message = "쿠키(SESSION·XSRF-TOKEN)·쿼리 전부 전달"
  }
  assert {
    condition = (
      one([for o in aws_cloudfront_distribution.this.origin : o.domain_name if o.origin_id == "api"]) == "ec2-3-1-2-3.compute-1.amazonaws.com"
      && one(one([for o in aws_cloudfront_distribution.this.origin : o.custom_origin_config if o.origin_id == "api"])).origin_protocol_policy == "http-only"
      && one(one([for o in aws_cloudfront_distribution.this.origin : o.custom_origin_config if o.origin_id == "api"])).http_port == 8080
    )
    error_message = "api origin: EIP DNS, HTTP 8080"
  }
}

# TASK-16#3 /uploads/*: uploads 버킷(OAC), CachingOptimized, GET/HEAD
run "uploads_behavior" {
  command = apply

  assert {
    condition     = one([for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.target_origin_id if b.path_pattern == "/uploads/*"]) == "uploads"
    error_message = "/uploads/* → uploads origin"
  }
  assert {
    condition     = one([for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.cache_policy_id if b.path_pattern == "/uploads/*"]) == "658327ea-f89d-4fab-a63d-7e88639e58f6"
    error_message = "CachingOptimized"
  }
  assert {
    condition     = toset(one([for b in aws_cloudfront_distribution.this.ordered_cache_behavior : b.allowed_methods if b.path_pattern == "/uploads/*"])) == toset(["GET", "HEAD"])
    error_message = "읽기 전용(업로드는 presigned URL 로 S3 에 직접)"
  }
  assert {
    condition = (
      one([for o in aws_cloudfront_distribution.this.origin : o.domain_name if o.origin_id == "uploads"]) == "board-test-uploads.s3.us-east-1.amazonaws.com"
      && one([for o in aws_cloudfront_distribution.this.origin : o.origin_access_control_id if o.origin_id == "uploads"]) == aws_cloudfront_origin_access_control.s3.id
    )
    error_message = "uploads origin: 버킷 regional 도메인 + OAC"
  }
}

# TASK-16#3 배포 공통: PriceClass_100, 기본 인증서(*.cloudfront.net), 사용자 지정 오류 응답 없음(/api JSON 오류를 HTML 로 덮지 않는다)
run "distribution_defaults" {
  command = apply

  assert {
    condition     = aws_cloudfront_distribution.this.price_class == "PriceClass_100" && aws_cloudfront_distribution.this.enabled
    error_message = "PriceClass_100"
  }
  assert {
    condition     = one(aws_cloudfront_distribution.this.viewer_certificate).cloudfront_default_certificate && length(aws_cloudfront_distribution.this.aliases) == 0
    error_message = "도메인이 없으면 *.cloudfront.net 기본 인증서(decision-9)"
  }
  assert {
    condition     = length(aws_cloudfront_distribution.this.custom_error_response) == 0
    error_message = "custom_error_response 는 배포 전체(/api/* 포함)에 걸려 API 404·403 JSON 을 덮으므로 쓰지 않는다"
  }
  assert {
    condition     = aws_cloudfront_distribution.this.default_root_object == "index.html"
    error_message = "/ → index.html"
  }
  assert {
    condition     = output.distribution_arn == "arn:aws:cloudfront::123456789012:distribution/E2TEST" && output.web_bucket == "board-test-web"
    error_message = "출력: distribution_arn, web_bucket"
  }
}

# TASK-16#3 사용자 도메인: aliases + ACM 인증서(us-east-1)를 주면 SNI + TLSv1.2_2021
run "custom_domain" {
  command = apply

  variables {
    aliases             = ["board.example.com"]
    acm_certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/abc"
  }

  assert {
    condition     = toset(aws_cloudfront_distribution.this.aliases) == toset(["board.example.com"])
    error_message = "aliases"
  }
  assert {
    condition = (
      one(aws_cloudfront_distribution.this.viewer_certificate).acm_certificate_arn == "arn:aws:acm:us-east-1:123456789012:certificate/abc"
      && one(aws_cloudfront_distribution.this.viewer_certificate).ssl_support_method == "sni-only"
      && one(aws_cloudfront_distribution.this.viewer_certificate).minimum_protocol_version == "TLSv1.2_2021"
    )
    error_message = "ACM 인증서, sni-only, TLSv1.2_2021"
  }
}

run "aliases_without_certificate_rejected" {
  command = plan

  variables {
    aliases = ["board.example.com"]
  }

  expect_failures = [var.aliases]
}
