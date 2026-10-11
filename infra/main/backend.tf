# 원격 state: infra/bootstrap 이 만든 S3 버킷. 값은 partial config 로 환경별 파일에서 받는다.
#   terraform init -backend-config=envs/prod.backend.hcl
# 잠금은 S3 native lockfile(use_lockfile = true, Terraform >= 1.10). DynamoDB 테이블을 쓰지 않는다.
terraform {
  backend "s3" {}
}
