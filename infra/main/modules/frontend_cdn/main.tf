# 프론트·단일 오리진 CDN (TASK-16#3, decision-3·10).
#   기본      → web S3(OAC), CachingOptimized, CF Function: viewer-request /x/ → /x/index.html, 모르는 페이지 → /404.html,
#              viewer-response 로 그 응답을 상태 404 로(TASK-34#2)
#   /api/*    → EC2(EIP DNS) HTTP:8080, CachingDisabled + API 전용 origin request policy(아래 설명)
#   /uploads/* → uploads S3(OAC), CachingOptimized
# + 백엔드 이미지 ECR 리포.

locals {
  # AWS 관리형 cache policy ID 는 모든 계정에서 같은 고정 값이다(CloudFront 개발자 안내서 "Use managed cache policies").
  # data source 로 이름 조회를 하지 않아 plan 이 더 단순하고 테스트도 오프라인으로 값을 검사할 수 있다.
  caching_optimized_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6" # Managed-CachingOptimized
  caching_disabled_policy_id  = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad" # Managed-CachingDisabled

  web_origin_id     = "web"
  api_origin_id     = "api"
  uploads_origin_id = "uploads"
}

# --- ECR -------------------------------------------------------------------

resource "aws_ecr_repository" "api" {
  name                 = var.ecr_repository_name
  image_tag_mutability = "MUTABLE" # 배포 태그는 git sha. 롤백은 이전 sha 태그로 한다(TASK-17)
  force_delete         = var.ecr_force_delete

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = var.tags
}

resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep last ${var.ecr_keep_images} images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = var.ecr_keep_images
      }
      action = { type = "expire" }
    }]
  })
}

# --- web 버킷 ----------------------------------------------------------------

resource "aws_s3_bucket" "web" {
  bucket        = var.web_bucket_name
  force_destroy = var.web_force_destroy
  tags          = var.tags
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket = aws_s3_bucket.web.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "web" {
  bucket = aws_s3_bucket.web.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "web" {
  bucket = aws_s3_bucket.web.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.web.arn, "${aws_s3_bucket.web.arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      },
      {
        Sid       = "AllowCloudFrontRead"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = ["s3:GetObject"]
        Resource  = "${aws_s3_bucket.web.arn}/*"
        Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.this.arn } }
      },
      {
        # ListBucket 이 없으면 없는 키에 S3 가 403 을 준다. 404 로 받도록 허용한다
        Sid       = "AllowCloudFrontList"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = ["s3:ListBucket"]
        Resource  = aws_s3_bucket.web.arn
        Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.this.arn } }
      },
    ]
  })

  depends_on = [aws_s3_bucket_public_access_block.web]
}

# --- CloudFront --------------------------------------------------------------

# web·uploads 두 S3 오리진이 같이 쓴다
resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "${var.name}-s3"
  description                       = "OAC for ${var.name} web/uploads buckets"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_function" "index_rewrite" {
  name    = "${var.name}-index-rewrite"
  runtime = "cloudfront-js-2.0"
  comment = "Next static export: /x/ -> /x/index.html, unknown page -> /404.html (404)"
  publish = true
  code    = file("${path.module}/functions/index_rewrite.js")
}

# /api/* origin request policy.
# - 모든 viewer 헤더(allViewer…): Authorization 은 cache policy 키나 origin request policy 에 있어야 전달되는데,
#   CachingDisabled 는 키를 가질 수 없고 Authorization 을 whitelist 에 넣을 수도 없다. "모든 viewer 헤더" 정책이면 전달된다(AWS 문서).
# - + CloudFront-Viewer-Address: 앱의 CLIENT_IP_HEADER(TASK-7 레이트리밋). 관리형 AllViewerExceptHostHeader 에는 헤더를 더할 수 없고
#   allExcept 동작은 CloudFront 헤더와 같이 쓸 수 없어서 allViewerAndWhitelistCloudFront 로 명시한다.
# - 그 결과 viewer Host(…cloudfront.net 또는 사용자 도메인)도 EC2 로 간다. 오리진은 가상 호스트 없는 HTTP(Tomcat)라 문제없고,
#   OAuth redirect-uri 는 어차피 PUBLIC_BASE_URL 로 고정한다(TASK-4).
# - 쿠키(SESSION, XSRF-TOKEN)·쿼리 전부.
resource "aws_cloudfront_origin_request_policy" "api" {
  name    = "${var.name}-api"
  comment = "all viewer headers/cookies/query + CloudFront-Viewer-Address"

  cookies_config {
    cookie_behavior = "all"
  }

  headers_config {
    header_behavior = "allViewerAndWhitelistCloudFront"
    headers {
      items = ["CloudFront-Viewer-Address", "CloudFront-Forwarded-Proto"]
    }
  }

  query_strings_config {
    query_string_behavior = "all"
  }
}

resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  comment             = var.name
  is_ipv6_enabled     = true
  http_version        = "http2and3"
  price_class         = var.price_class
  default_root_object = "index.html"
  aliases             = var.aliases

  origin {
    origin_id                = local.web_origin_id
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id                = local.uploads_origin_id
    domain_name              = var.uploads_bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id   = local.api_origin_id
    domain_name = var.api_origin_domain

    custom_origin_config {
      http_port                = var.api_origin_port
      https_port               = 443
      origin_protocol_policy   = "http-only" # CloudFront→EC2 는 HTTP. SG 가 CloudFront prefix list 로만 연다
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_read_timeout      = 30
      origin_keepalive_timeout = 5
    }
  }

  default_cache_behavior {
    target_origin_id       = local.web_origin_id
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = local.caching_optimized_policy_id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.index_rewrite.arn
    }

    # 같은 함수가 /404.html 로 보낸 응답의 200 을 404 로 바꾼다(TASK-34#2). 기본 동작에만 있어 /api/* 응답은 거치지 않는다(B1).
    function_association {
      event_type   = "viewer-response"
      function_arn = aws_cloudfront_function.index_rewrite.arn
    }
  }

  # /api/*: 같은 오리진이라 CORS·mixed content 가 없다(decision-3). 트레일링 슬래시 리라이트를 하지 않는다(TASK-13).
  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = local.api_origin_id
    viewer_protocol_policy   = "https-only"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = local.caching_disabled_policy_id
    origin_request_policy_id = aws_cloudfront_origin_request_policy.api.id
  }

  ordered_cache_behavior {
    path_pattern           = "/uploads/*"
    target_origin_id       = local.uploads_origin_id
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = local.caching_optimized_policy_id
  }

  # custom_error_response 는 두지 않는다: 배포 전체(/api/* 포함)에 적용되어 API 의 404·403 JSON 을 HTML 로 덮는다(TASK-34#B1).
  # 모르는 페이지 경로는 기본 동작의 CF Function 이 /404.html(상태 404)로 보낸다(TASK-34#2). 없는 파일(확장자 있는 경로)은 S3 404 그대로
  # (web 버킷 정책의 ListBucket 덕에 403 이 아니라 404).

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = var.acm_certificate_arn == null
    acm_certificate_arn            = var.acm_certificate_arn
    ssl_support_method             = var.acm_certificate_arn == null ? null : "sni-only"
    minimum_protocol_version       = var.acm_certificate_arn == null ? "TLSv1" : "TLSv1.2_2021"
  }

  tags = var.tags
}
