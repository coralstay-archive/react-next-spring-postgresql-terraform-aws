# infra/main prod 변수. 사용:
#   terraform -chdir=infra/main init -backend-config=envs/prod.backend.hcl
#   terraform -chdir=infra/main plan -var-file=envs/prod.tfvars
# 버킷 이름·Cognito prefix 는 전역 고유라 계정 ID 등을 붙여 바꾼다(CHANGE-ME).

region            = "us-east-1"
environment       = "prod"
name              = "board-prod"
availability_zone = "us-east-1a"
instance_type     = "t4g.small"
data_volume_size  = 20

web_bucket_name     = "board-prod-web-CHANGE-ME"
uploads_bucket_name = "board-prod-uploads-CHANGE-ME"
ecr_repository_name = "board-api"

ssm_parameter_path = "/board/prod"
log_group_name     = "/board/prod"

cognito_domain_prefix       = "board-prod-login-change-me"
cognito_deletion_protection = true
# 로컬 개발에서도 Cognito 로그인을 쓰려면:
# cognito_extra_callback_urls = ["http://localhost:3000/api/login/oauth2/code/cognito"]
# cognito_extra_logout_urls   = ["http://localhost:3000/"]

# 도메인 없이 *.cloudfront.net 으로 시작한다(decision-9). 도메인 구입 후 채우면 dns_cert 가 켜진다.
domain_name               = ""
subject_alternative_names = []
create_hosted_zone        = false

# GitHub Actions 배포(OIDC). "owner/repo" 를 넣으면 ci_oidc 가 켜진다.
github_repository = ""
github_branch     = "main"
