output "role_arn" {
  description = "GitHub Actions 가 assume 할 배포 role ARN (aws-actions/configure-aws-credentials 의 role-to-assume)"
  value       = aws_iam_role.deploy.arn
}

output "oidc_provider_arn" {
  description = "GitHub OIDC provider ARN"
  value       = local.oidc_provider_arn
}
