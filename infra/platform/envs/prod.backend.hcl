# infra/platform prod backend 설정. bucket 은 infra/main 과 같은 bootstrap 버킷(terraform -chdir=infra/bootstrap output -raw state_bucket).
# key 는 infra/main(main/prod/terraform.tfstate)과 나눈다 — 두 스택이 서로의 state·잠금을 건드리지 않는다.
bucket       = "board-tfstate-CHANGE-ME"
key          = "platform/terraform.tfstate"
region       = "us-east-1"
encrypt      = true
use_lockfile = true
