# GitHub Actions → AWS (TASK-16#4). 장기 키 없이 OIDC 로 배포 role 을 assume 한다.
# trust 는 repo:<owner>/<repo>:ref:refs/heads/<branch> 정확히 일치 — PR·포크·다른 브랜치는 assume 못 한다.
# 권한은 배포에 필요한 것만: ECR push, web 버킷 sync, CloudFront invalidation, 이 인스턴스에 AWS-RunShellScript.
# CI 는 terraform state·SSM 파라미터(시크릿)·IAM 을 건드리지 않는다. 배포 대상 값(ECR URL·버킷·배포 ID·인스턴스 ID)은
# GitHub 리포 variables 로 넣는다(terraform output 참고, TASK-17).
# PR 용 읽기 전용 plan role 은 두지 않는다: PR CI 는 mock_provider 테스트(make test)만 돌린다.

data "aws_region" "current" {}

locals {
  oidc_host         = "token.actions.githubusercontent.com"
  oidc_provider_arn = var.existing_oidc_provider_arn != null ? var.existing_oidc_provider_arn : aws_iam_openid_connect_provider.github[0].arn
  region            = data.aws_region.current.region
}

resource "aws_iam_openid_connect_provider" "github" {
  count = var.existing_oidc_provider_arn == null ? 1 : 0

  url            = "https://${local.oidc_host}"
  client_id_list = ["sts.amazonaws.com"]
  # thumbprint_list 는 생략한다: IAM 이 GitHub OIDC 를 신뢰 CA 로 검증한다
  tags = var.tags
}

resource "aws_iam_role" "deploy" {
  name                 = "${var.name}-gha-deploy"
  max_session_duration = 3600
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.oidc_provider_arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
          "${local.oidc_host}:sub" = ["repo:${var.github_repository}:ref:refs/heads/${var.github_branch}"]
        }
      }
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy" "deploy" {
  name = "deploy"
  role = aws_iam_role.deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload",
          "ecr:PutImage", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:DescribeImages",
        ]
        Resource = var.ecr_repository_arn
      },
      {
        Sid      = "WebBucketList"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = var.web_bucket_arn
      },
      {
        Sid      = "WebBucketSync"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
        Resource = "${var.web_bucket_arn}/*"
      },
      {
        Sid      = "Invalidate"
        Effect   = "Allow"
        Action   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
        Resource = var.distribution_arn
      },
      {
        Sid    = "DeployCommand"
        Effect = "Allow"
        Action = ["ssm:SendCommand"]
        Resource = [
          var.instance_arn,
          "arn:aws:ssm:${local.region}::document/AWS-RunShellScript",
        ]
      },
      {
        # 명령 결과 조회는 리소스 단위 권한을 지원하지 않는다
        Sid      = "DeployCommandResult"
        Effect   = "Allow"
        Action   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
        Resource = "*"
      },
    ]
  })
}
