# TASK-16#4 ci_oidc 모듈 테스트: GitHub Actions OIDC provider + 배포 role(main 브랜치만, 최소 권한). mock_provider(오프라인).

mock_provider "aws" {
  mock_resource "aws_iam_openid_connect_provider" {
    defaults = {
      arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    }
  }
}

override_data {
  target = data.aws_region.current
  values = {
    region = "us-east-1"
  }
}

variables {
  name               = "board-test"
  github_repository  = "octo/board"
  ecr_repository_arn = "arn:aws:ecr:us-east-1:123456789012:repository/board-api"
  web_bucket_arn     = "arn:aws:s3:::board-test-web"
  distribution_arn   = "arn:aws:cloudfront::123456789012:distribution/E2TEST"
  instance_arn       = "arn:aws:ec2:us-east-1:123456789012:instance/i-0123"
}

# TASK-16#4 OIDC provider: GitHub 토큰 발급자, audience sts.amazonaws.com
run "oidc_provider" {
  command = apply

  assert {
    condition     = aws_iam_openid_connect_provider.github[0].url == "https://token.actions.githubusercontent.com"
    error_message = "GitHub Actions OIDC 발급자"
  }
  assert {
    condition     = toset(aws_iam_openid_connect_provider.github[0].client_id_list) == toset(["sts.amazonaws.com"])
    error_message = "audience sts.amazonaws.com"
  }
}

# TASK-16#4 trust: 이 리포의 main 브랜치 push 만 assume (PR·포크·다른 브랜치 불가)
run "trust_main_branch_only" {
  command = apply

  assert {
    condition     = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Action == "sts:AssumeRoleWithWebIdentity"
    error_message = "웹 ID 연동"
  }
  assert {
    condition     = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Principal.Federated == "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    error_message = "이 OIDC provider 를 신뢰"
  }
  assert {
    condition     = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    error_message = "aud 검사"
  }
  assert {
    condition     = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"] == ["repo:octo/board:ref:refs/heads/main"]
    error_message = "sub 는 repo:octo/board:ref:refs/heads/main 정확히 일치(StringLike 와일드카드 금지)"
  }
  assert {
    condition     = output.role_arn == aws_iam_role.deploy.arn
    error_message = "role_arn 출력"
  }
}

# TASK-16#4 권한: ECR push(이 리포), web 버킷 sync, 이 배포 invalidation, 이 인스턴스에 AWS-RunShellScript 만 SendCommand
run "least_privilege" {
  command = apply

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      contains(s.Action, "ecr:PutImage") && contains(s.Action, "ecr:CompleteLayerUpload") && s.Resource == "arn:aws:ecr:us-east-1:123456789012:repository/board-api"
    ])
    error_message = "ECR push 는 이 리포만"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      contains(s.Action, "s3:PutObject") && contains(s.Action, "s3:DeleteObject") && s.Resource == "arn:aws:s3:::board-test-web/*"
    ])
    error_message = "web 버킷 객체 쓰기·삭제(s3 sync --delete)"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      contains(s.Action, "s3:ListBucket") && s.Resource == "arn:aws:s3:::board-test-web"
    ])
    error_message = "web 버킷 목록(sync 비교)"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      contains(s.Action, "cloudfront:CreateInvalidation") && s.Resource == "arn:aws:cloudfront::123456789012:distribution/E2TEST"
    ])
    error_message = "이 배포만 invalidation"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      s.Action == ["ssm:SendCommand"] && toset(s.Resource) == toset([
        "arn:aws:ec2:us-east-1:123456789012:instance/i-0123",
        "arn:aws:ssm:us-east-1::document/AWS-RunShellScript",
      ])
    ])
    error_message = "SendCommand 는 이 인스턴스 + AWS-RunShellScript 문서만"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement : contains(s.Action, "ssm:GetCommandInvocation")
    ])
    error_message = "명령 결과 폴링"
  }
  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      !anytrue([for a in flatten([s.Action]) : a == "*" || endswith(a, ":*")])
    ])
    error_message = "서비스 와일드카드 Action 금지"
  }
  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.deploy.policy).Statement :
      !anytrue([for a in flatten([s.Action]) : startswith(a, "iam:") || startswith(a, "ssm:GetParameter") || a == "ssm:PutParameter"])
    ])
    error_message = "CI 는 IAM·시크릿(SSM 파라미터)을 읽거나 바꾸지 않는다"
  }
}

# TASK-16#4 같은 계정에 GitHub OIDC provider 가 이미 있으면 만들지 않고 그 ARN 을 쓴다(URL 당 하나만 만들 수 있다)
run "existing_oidc_provider" {
  command = apply

  variables {
    existing_oidc_provider_arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
  }

  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 0
    error_message = "기존 provider 를 쓰면 만들지 않는다"
  }
  assert {
    condition     = jsondecode(aws_iam_role.deploy.assume_role_policy).Statement[0].Principal.Federated == "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    error_message = "기존 provider 를 신뢰"
  }
}

# TASK-16#4 github_repository 는 owner/repo 형식
run "invalid_repository_rejected" {
  command = plan

  variables {
    github_repository = "https://github.com/octo/board"
  }

  expect_failures = [var.github_repository]
}
