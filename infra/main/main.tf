# 운영 스택 (TASK-16). 모듈 구성과 데이터 흐름:
#   network ─▶ backend_host(EC2·EIP) ─EIP DNS─▶ frontend_cdn(CloudFront /api/*)
#   uploads(bucket) ─regional domain─▶ frontend_cdn(/uploads/*) ─distribution ARN─▶ uploads(bucket policy)
#   uploads.app_policy_json ─▶ backend_host(인스턴스 role)      frontend_cdn.ECR ─▶ backend_host(pull 권한)
#   frontend_cdn(도메인) ─▶ PUBLIC_BASE_URL ─▶ cognito(콜백)·uploads(CORS)
#   dns_cert(인증서) ─▶ frontend_cdn(aliases) ─▶ dns_cert(alias 레코드)
# 모듈 사이 참조가 양방향인 곳(uploads↔CDN, dns_cert↔CDN)도 Terraform 은 리소스 단위로 그래프를 만들어 순환이 없다:
# 버킷·인증서 리소스 자체는 배포 값을 참조하지 않고, 배포 값을 쓰는 버킷 정책·CORS·alias 레코드는 별도 리소스다.
#
# 시크릿(SSM SecureString)은 여기서 만들지 않는다 — 값이 state 에 남지 않게 TASK-17 put-secrets.sh 가 넣는다.

locals {
  custom_domain   = var.domain_name != ""
  public_host     = local.custom_domain ? var.domain_name : module.frontend_cdn.domain_name
  public_base_url = "https://${local.public_host}"
}

module "network" {
  source = "./modules/network"

  name              = var.name
  availability_zone = var.availability_zone
}

module "uploads" {
  source = "./modules/uploads"

  bucket_name                 = var.uploads_bucket_name
  allowed_origins             = [local.public_base_url]
  cloudfront_distribution_arn = module.frontend_cdn.distribution_arn
  force_destroy               = var.uploads_force_destroy
}

module "backend_host" {
  source = "./modules/backend_host"

  name               = var.name
  vpc_id             = module.network.vpc_id
  subnet_id          = module.network.subnet_id
  availability_zone  = module.network.availability_zone
  instance_type      = var.instance_type
  data_volume_size   = var.data_volume_size
  ecr_repository_arn = module.frontend_cdn.ecr_repository_arn
  log_group_name     = var.log_group_name
  ssm_parameter_path = var.ssm_parameter_path
  extra_policy_jsons = [module.uploads.app_policy_json]
}

module "dns_cert" {
  source = "./modules/dns_cert"
  count  = local.custom_domain ? 1 : 0

  providers = {
    aws = aws.us_east_1 # ACM 인증서는 us-east-1 (Route53 은 전역)
  }

  domain_name               = var.domain_name
  subject_alternative_names = var.subject_alternative_names
  zone_name                 = var.hosted_zone_name
  create_hosted_zone        = var.create_hosted_zone
  cloudfront_domain_name    = module.frontend_cdn.domain_name
  cloudfront_hosted_zone_id = module.frontend_cdn.hosted_zone_id
}

module "frontend_cdn" {
  source = "./modules/frontend_cdn"

  name                                = var.name
  web_bucket_name                     = var.web_bucket_name
  ecr_repository_name                 = var.ecr_repository_name
  api_origin_domain                   = module.backend_host.public_dns
  uploads_bucket_regional_domain_name = module.uploads.bucket_regional_domain_name
  aliases                             = local.custom_domain ? module.dns_cert[0].aliases : []
  acm_certificate_arn                 = local.custom_domain ? module.dns_cert[0].certificate_arn : null
}

module "cognito" {
  source = "./modules/cognito"

  name                = var.name
  public_base_url     = local.public_base_url
  domain_prefix       = var.cognito_domain_prefix
  deletion_protection = var.cognito_deletion_protection

  # 추가 URL 이 없으면 null → 모듈 기본값(<public_base_url>/api/login/oauth2/code/cognito, <public_base_url>/)
  callback_urls = length(var.cognito_extra_callback_urls) == 0 ? null : concat(["${local.public_base_url}/api/login/oauth2/code/cognito"], var.cognito_extra_callback_urls)
  logout_urls   = length(var.cognito_extra_logout_urls) == 0 ? null : concat(["${local.public_base_url}/"], var.cognito_extra_logout_urls)
}

module "ci_oidc" {
  source = "./modules/ci_oidc"
  count  = var.github_repository == "" ? 0 : 1

  name                       = var.name
  github_repository          = var.github_repository
  github_branch              = var.github_branch
  existing_oidc_provider_arn = var.existing_github_oidc_provider_arn
  ecr_repository_arn         = module.frontend_cdn.ecr_repository_arn
  web_bucket_arn             = module.frontend_cdn.web_bucket_arn
  distribution_arn           = module.frontend_cdn.distribution_arn
  instance_arn               = module.backend_host.instance_arn
}
