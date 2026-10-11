# TASK-16#1 bootstrap 테스트: Terraform state 를 담을 S3 버킷. mock_provider 라 AWS 자격 증명·네트워크 호출 없이 돈다.
# 버킷에 prevent_destroy 가 걸려 있어 apply 후 테스트 정리(destroy)가 막히므로 plan 으로만 검사한다.

mock_provider "aws" {}

# 정책 Resource 를 plan 단계에서 계산하려고 버킷 arn 을 고정한다
override_resource {
  target          = aws_s3_bucket.state
  override_during = plan
  values = {
    id  = "board-tfstate-123456789012"
    arn = "arn:aws:s3:::board-tfstate-123456789012"
  }
}

variables {
  state_bucket_name = "board-tfstate-123456789012"
}

# TASK-16#1 state 버킷: 이름은 변수, 버전 관리 켬, SSE, 퍼블릭 차단 4종, ACL 비활성
run "state_bucket_hardened" {
  command = plan

  assert {
    condition     = aws_s3_bucket.state.bucket == "board-tfstate-123456789012"
    error_message = "버킷 이름은 var.state_bucket_name"
  }
  assert {
    condition     = aws_s3_bucket.state.force_destroy == false
    error_message = "state 버킷은 객체가 있으면 지우지 않는다"
  }
  assert {
    condition     = one(aws_s3_bucket_versioning.state.versioning_configuration).status == "Enabled"
    error_message = "state 는 버전 관리로 이전 버전을 되살릴 수 있어야 한다"
  }
  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.state.rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"
    error_message = "기본 암호화(SSE-S3)"
  }
  assert {
    condition = (
      aws_s3_bucket_public_access_block.state.block_public_acls
      && aws_s3_bucket_public_access_block.state.block_public_policy
      && aws_s3_bucket_public_access_block.state.ignore_public_acls
      && aws_s3_bucket_public_access_block.state.restrict_public_buckets
    )
    error_message = "퍼블릭 접근 4종 차단"
  }
  assert {
    condition     = one(aws_s3_bucket_ownership_controls.state.rule).object_ownership == "BucketOwnerEnforced"
    error_message = "ACL 비활성(BucketOwnerEnforced)"
  }
}

# TASK-16#1 HTTPS 강제 정책 + 오래된 버전 정리(lockfile 은 S3 조건부 쓰기라 DynamoDB 가 필요 없다)
run "policy_and_lifecycle" {
  command = plan

  assert {
    condition     = jsondecode(aws_s3_bucket_policy.state.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false"
    error_message = "aws:SecureTransport=false 거부"
  }
  assert {
    condition     = one([for r in aws_s3_bucket_lifecycle_configuration.state.rule : one(r.noncurrent_version_expiration).noncurrent_days if r.id == "expire-old-state-versions"]) == 90
    error_message = "이전 state 버전은 90일 보관"
  }
}

# TASK-16#1 출력: main 스택의 -backend-config 에 넣을 값(bucket, key, region, use_lockfile = true)
run "backend_config_output" {
  command = plan

  assert {
    condition     = output.state_bucket == "board-tfstate-123456789012"
    error_message = "state_bucket 출력"
  }
  assert {
    condition     = strcontains(output.backend_config, "use_lockfile = true") && strcontains(output.backend_config, "bucket       = \"board-tfstate-123456789012\"")
    error_message = "backend_config 는 S3 native lockfile 을 쓴다"
  }
  assert {
    condition     = strcontains(output.backend_config, "region       = \"us-east-1\"")
    error_message = "리전 기본 us-east-1(decision-1)"
  }
}

run "invalid_bucket_name_rejected" {
  command = plan

  variables {
    state_bucket_name = "Bad_Name"
  }

  expect_failures = [var.state_bucket_name]
}
