# infra/main prod backend 설정. bucket 은 bootstrap 출력(terraform -chdir=infra/bootstrap output -raw backend_config)으로 바꾼다.
bucket       = "board-tfstate-CHANGE-ME"
key          = "main/prod/terraform.tfstate"
region       = "us-east-1"
encrypt      = true
use_lockfile = true
