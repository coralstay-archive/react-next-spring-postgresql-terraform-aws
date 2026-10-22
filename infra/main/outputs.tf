output "cloudfront_domain" {
  description = "*.cloudfront.net 도메인"
  value       = module.frontend_cdn.domain_name
}

output "distribution_id" {
  description = "CloudFront 배포 ID (deploy-frontend invalidation)"
  value       = module.frontend_cdn.distribution_id
}

output "public_base_url" {
  description = "브라우저가 보는 공개 주소 → 앱 PUBLIC_BASE_URL, OAuth 콜백 기준"
  value       = local.public_base_url
}

output "instance_id" {
  description = "EC2 instance ID (SSM send-command 대상)"
  value       = module.backend_host.instance_id
}

output "eip_public_dns" {
  description = "EIP public DNS (/api/* origin)"
  value       = module.backend_host.public_dns
}

output "ecr_repository_url" {
  description = "백엔드 이미지 ECR URL"
  value       = module.frontend_cdn.ecr_repository_url
}

output "web_bucket" {
  description = "정적 프론트 버킷 (deploy-frontend s3 sync)"
  value       = module.frontend_cdn.web_bucket
}

output "uploads_bucket" {
  description = "첨부 버킷 → 앱 UPLOADS_BUCKET"
  value       = module.uploads.bucket_name
}

output "cognito" {
  description = "Cognito 앱 설정 → COGNITO_ISSUER_URI, COGNITO_CLIENT_ID, COGNITO_DOMAIN"
  value = {
    issuer_uri = module.cognito.issuer_uri
    client_id  = module.cognito.client_id
    domain_url = module.cognito.domain_url
  }
}

output "cognito_client_secret" {
  description = "COGNITO_CLIENT_SECRET (terraform output -raw cognito_client_secret | put-secrets.sh 로 SSM 에 넣는다)"
  value       = module.cognito.client_secret
  sensitive   = true
}

output "ci_role_arn" {
  description = "GitHub Actions 배포 role ARN (github_repository 미지정이면 null)"
  value       = length(module.ci_oidc) == 0 ? null : module.ci_oidc[0].role_arn
}

output "log_group" {
  description = "앱 로그 그룹"
  value       = module.backend_host.log_group_name
}

output "ssm_parameter_path" {
  description = "앱 시크릿 SSM 경로 (put-secrets.sh·render-env.sh)"
  value       = var.ssm_parameter_path
}

output "data_mount_path" {
  description = "EC2 의 PostgreSQL 데이터 EBS 마운트 경로 (deploy compose 의 postgres 바인드 원본)"
  value       = module.backend_host.data_mount_path
}

output "deploy_dir" {
  description = "EC2 배포 디렉터리"
  value       = module.backend_host.deploy_dir
}

output "region" {
  description = "리전"
  value       = var.region
}

output "dns_name_servers" {
  description = "create_hosted_zone = true 일 때 등록기관에 위임할 NS"
  value       = local.custom_domain ? module.dns_cert[0].name_servers : []
}

# 비밀이 아닌 운영 앱 env. TASK-17 이 SSM(/board/prod/*)에 함께 넣거나 env 파일에 쓴다. 시크릿은 넣지 않는다.
output "app_env" {
  description = "운영 앱 env 중 Terraform 이 아는 값(비밀 아님)"
  value = {
    PUBLIC_BASE_URL          = local.public_base_url
    COOKIE_SECURE            = "true"
    CLIENT_IP_HEADER         = "CloudFront-Viewer-Address"
    UPLOADS_BUCKET           = module.uploads.bucket_name
    UPLOADS_REGION           = var.region # 앱 기본값(ap-northeast-2)과 다르므로 반드시 명시(TASK-11)
    UPLOADS_PUBLIC_BASE_PATH = "/uploads/"
    COGNITO_ISSUER_URI       = module.cognito.issuer_uri
    COGNITO_CLIENT_ID        = module.cognito.client_id
    COGNITO_DOMAIN           = module.cognito.domain_url
    SMS_AWS_REGION           = var.region
    SMS_PROVIDER             = "aws" # 운영 모드는 aws 외에는 기동하지 않는다(TASK-21#4)
  }
}
