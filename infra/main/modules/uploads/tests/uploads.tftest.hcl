# TASK-11#4 uploads 모듈 테스트. mock_provider 라 AWS 자격 증명·네트워크 호출 없이 돈다.
# 실행: scripts/test.sh (terraform init -backend=false && terraform test)

mock_provider "aws" {}

# mock 은 계산 값(arn 등)을 임의 문자열로 채운다. 정책의 Resource 를 검사하려고 버킷 arn·id 를 고정한다.
override_resource {
  target = aws_s3_bucket.this
  values = {
    id  = "board-test-uploads"
    arn = "arn:aws:s3:::board-test-uploads"
  }
}

variables {
  bucket_name     = "board-test-uploads"
  allowed_origins = ["https://d111.cloudfront.net", "http://localhost:3000"]
}

# TASK-11#4 버킷 기본 보안: 퍼블릭 차단 4종, ACL 비활성(BucketOwnerEnforced), SSE(AES256), 실수로 지우지 않게 force_destroy 기본 false
run "bucket_hardened" {
  command = apply

  assert {
    condition     = aws_s3_bucket.this.bucket == "board-test-uploads" && aws_s3_bucket.this.force_destroy == false
    error_message = "버킷 이름은 var.bucket_name, force_destroy 기본 false"
  }
  assert {
    condition = (
      aws_s3_bucket_public_access_block.this.block_public_acls
      && aws_s3_bucket_public_access_block.this.block_public_policy
      && aws_s3_bucket_public_access_block.this.ignore_public_acls
      && aws_s3_bucket_public_access_block.this.restrict_public_buckets
    )
    error_message = "퍼블릭 접근은 모두 막는다(서빙은 CloudFront OAC 로만)"
  }
  assert {
    condition     = one(aws_s3_bucket_ownership_controls.this.rule).object_ownership == "BucketOwnerEnforced"
    error_message = "ACL 비활성(BucketOwnerEnforced)"
  }
  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.this.rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"
    error_message = "기본 암호화 SSE-S3(AES256)"
  }
}

# TASK-11#4 CORS: 브라우저가 presigned URL 로 직접 PUT 한다 → allowed_origins 에서 PUT 허용, 서명된 헤더 content-type·x-amz-tagging 허용
run "cors_for_presigned_put" {
  command = apply

  assert {
    condition = alltrue([
      for rule in aws_s3_bucket_cors_configuration.this.cors_rule :
      toset(rule.allowed_origins) == toset(["https://d111.cloudfront.net", "http://localhost:3000"])
    ])
    error_message = "모든 CORS 규칙의 origin 은 var.allowed_origins"
  }
  assert {
    condition = anytrue([
      for rule in aws_s3_bucket_cors_configuration.this.cors_rule :
      contains(rule.allowed_methods, "PUT")
      && contains([for h in rule.allowed_headers : lower(h)], "content-type")
      && contains([for h in rule.allowed_headers : lower(h)], "x-amz-tagging")
    ])
    error_message = "PUT 규칙은 content-type·x-amz-tagging 헤더를 허용해야 한다(presign 이 서명한 헤더)"
  }
  # TASK-26#2 TASK-26#B1 presign 이 덮어쓰기 금지 If-None-Match: * 도 서명한다 → 브라우저 preflight 에 올라오므로 허용해야 PUT 이 된다
  assert {
    condition = anytrue([
      for rule in aws_s3_bucket_cors_configuration.this.cors_rule :
      contains(rule.allowed_methods, "PUT") && contains([for h in rule.allowed_headers : lower(h)], "if-none-match")
    ])
    error_message = "PUT 규칙은 If-None-Match 헤더를 허용해야 한다(TASK-26#2 덮어쓰기 금지)"
  }
  assert {
    condition = anytrue([
      for rule in aws_s3_bucket_cors_configuration.this.cors_rule : contains(rule.allowed_methods, "GET")
    ])
    error_message = "GET 도 허용(같은 origin 이 아닌 곳에서 이미지를 읽는 경우)"
  }
  assert {
    condition = alltrue([
      for rule in aws_s3_bucket_cors_configuration.this.cors_rule :
      !contains(rule.allowed_methods, "DELETE") && !contains(rule.allowed_origins, "*")
    ])
    error_message = "DELETE 나 와일드카드 origin 은 허용하지 않는다"
  }
}

# TASK-11#4 미확정 객체 정리: uploads/ 아래 status=pending 태그가 남은 객체는 1일 뒤 만료(confirm 이 태그를 지운다)
run "pending_lifecycle" {
  command = apply

  assert {
    condition     = one([for r in aws_s3_bucket_lifecycle_configuration.this.rule : r.status if r.id == "expire-pending-uploads"]) == "Enabled"
    error_message = "미확정 만료 규칙(expire-pending-uploads)이 켜져 있어야 한다"
  }
  assert {
    condition     = one([for r in aws_s3_bucket_lifecycle_configuration.this.rule : one(one(r.filter).and).prefix if r.id == "expire-pending-uploads"]) == "uploads/"
    error_message = "uploads/ 접두어만"
  }
  assert {
    condition     = one([for r in aws_s3_bucket_lifecycle_configuration.this.rule : one(one(r.filter).and).tags if r.id == "expire-pending-uploads"]) == tomap({ status = "pending" })
    error_message = "status=pending 태그가 남은 객체만(confirm 이 태그를 지운다)"
  }
  assert {
    condition     = one([for r in aws_s3_bucket_lifecycle_configuration.this.rule : one(r.expiration).days if r.id == "expire-pending-uploads"]) == 1
    error_message = "1일 뒤 만료"
  }
  assert {
    condition     = one([for r in aws_s3_bucket_lifecycle_configuration.this.rule : one(r.abort_incomplete_multipart_upload).days_after_initiation if r.id == "abort-incomplete-multipart"]) == 1
    error_message = "끝나지 않은 멀티파트 업로드도 정리"
  }
}

# TASK-11#4 버킷 정책: CloudFront 배포를 주지 않으면 HTTPS 강제만 있고 CloudFront 읽기 허용은 없다
run "policy_without_cloudfront" {
  command = apply

  assert {
    condition     = length(jsondecode(aws_s3_bucket_policy.this.policy).Statement) == 1
    error_message = "HTTPS 강제 문장 하나만"
  }
  assert {
    condition = (
      jsondecode(aws_s3_bucket_policy.this.policy).Statement[0].Effect == "Deny"
      && jsondecode(aws_s3_bucket_policy.this.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false"
    )
    error_message = "aws:SecureTransport=false 요청 거부"
  }
  assert {
    condition     = aws_s3_bucket_policy.this.bucket == "board-test-uploads"
    error_message = "정책은 이 버킷에"
  }
}

# TASK-11#4 CloudFront /uploads/* 서빙(OAC): 배포 ARN 을 주면 그 배포만 uploads/* 를 GetObject 할 수 있다
run "policy_with_cloudfront_oac" {
  command = apply

  variables {
    cloudfront_distribution_arn = "arn:aws:cloudfront::123456789012:distribution/E2TEST"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_s3_bucket_policy.this.policy).Statement :
      s.Effect == "Allow"
      && s.Principal.Service == "cloudfront.amazonaws.com"
      && s.Action == "s3:GetObject"
      && s.Resource == "arn:aws:s3:::board-test-uploads/uploads/*"
      && s.Condition.StringEquals["AWS:SourceArn"] == "arn:aws:cloudfront::123456789012:distribution/E2TEST"
    ])
    error_message = "CloudFront(OAC) 배포만 uploads/* 읽기"
  }
}

# TASK-11#4 앱(EC2 role)에 붙일 IAM 정책 출력: uploads/* 에 PutObject(+태그)·GetObject(HeadObject)·DeleteObject·DeleteObjectTagging
# TASK-26#1 + 버킷 ListBucket(s3:prefix uploads/*)
run "app_policy_output" {
  command = apply

  assert {
    condition = (
      toset(jsondecode(output.app_policy_json).Statement[0].Action) == toset([
        "s3:PutObject", "s3:PutObjectTagging", "s3:GetObject", "s3:GetObjectTagging", "s3:DeleteObject", "s3:DeleteObjectTagging",
      ])
      && jsondecode(output.app_policy_json).Statement[0].Resource == "arn:aws:s3:::board-test-uploads/uploads/*"
    )
    error_message = "앱 정책은 uploads/* 에 필요한 동작만"
  }
  # TASK-26#1 s3:ListBucket(uploads/ 접두어로 제한): 없으면 S3 가 없는 키의 HeadObject 에 404 대신 403 을 줘서 PUT 전 confirm 이
  # 400 attachment_not_uploaded 가 아니라 503 storage_unavailable 이 된다
  assert {
    condition = anytrue([
      for s in jsondecode(output.app_policy_json).Statement :
      s.Effect == "Allow" && s.Action == "s3:ListBucket" && s.Resource == "arn:aws:s3:::board-test-uploads"
      && s.Condition.StringLike["s3:prefix"] == "uploads/*"
    ])
    error_message = "앱 정책은 버킷에 s3:ListBucket 을 uploads/* 접두어로만 허용한다(없는 키 HEAD → 404)"
  }
  assert {
    condition     = length(jsondecode(output.app_policy_json).Statement) == 2
    error_message = "앱 정책은 객체 동작(uploads/*)과 접두어 제한 ListBucket 두 문장뿐"
  }
  assert {
    condition     = output.bucket_name == "board-test-uploads" && output.uploads_prefix == "uploads/"
    error_message = "버킷 이름·키 접두어 출력(앱 env UPLOADS_BUCKET)"
  }
}

# TASK-11#4 allowed_origins 는 https://<host> 또는 http://localhost[:port] 만(경로·와일드카드 불가)
run "invalid_origin_rejected" {
  command = plan

  variables {
    allowed_origins = ["http://example.com"]
  }

  expect_failures = [var.allowed_origins]
}

run "empty_origins_rejected" {
  command = plan

  variables {
    allowed_origins = []
  }

  expect_failures = [var.allowed_origins]
}
