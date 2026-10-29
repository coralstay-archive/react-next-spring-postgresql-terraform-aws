# 원격 state: infra/bootstrap 이 만든 S3 버킷을 infra/main 과 같이 쓰고 키만 나눈다(platform/terraform.tfstate).
#   terraform init -backend-config=envs/prod.backend.hcl
# 잠금은 S3 native lockfile(use_lockfile = true, Terraform >= 1.10). 고착됐을 때·state 백업은 docs/terraform-state.md.
terraform {
  backend "s3" {}
}
