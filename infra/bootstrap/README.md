# infra/bootstrap — Terraform state 버킷

`infra/main` 스택의 state 를 담을 S3 버킷을 만든다(TASK-16#1). 버전 관리·SSE-S3·퍼블릭 차단·HTTPS 강제·`prevent_destroy`.
잠금은 S3 native lockfile(`use_lockfile = true`, Terraform >= 1.10)이라 DynamoDB 테이블이 없다.

## 1회 적용 절차 (로컬 state)

bootstrap 자신은 원격 backend 가 없다(버킷이 생기기 전이니까). 계정당 한 번, 로컬 state 로 적용한다.

```bash
cd infra/bootstrap
aws sts get-caller-identity                     # 대상 계정 확인
terraform init
terraform apply -var "state_bucket_name=board-tfstate-$(aws sts get-caller-identity --query Account --output text)"
terraform output -raw backend_config > ../main/envs/prod.backend.hcl   # main 스택의 backend 설정
```

- 생성된 `terraform.tfstate` 는 git 에 올리지 않는다(`.gitignore`). 잃어버려도 버킷은 남으니
  `terraform import aws_s3_bucket.state <bucket>` 등으로 되살리거나 그대로 둔다.
- 원하면 apply 후 `backend "s3"` 블록을 추가하고 `terraform init -migrate-state` 로 bootstrap state 도 같은 버킷에 옮길 수 있다.
- 버킷은 `prevent_destroy` 라 `terraform destroy` 가 막힌다. 정말 지우려면 main 스택을 먼저 destroy 하고,
  `prevent_destroy` 를 잠시 지운 뒤 버킷의 모든 버전을 비우고 destroy 한다.

## 입력 / 출력

| 변수 | 기본값 | 설명 |
|---|---|---|
| `state_bucket_name` | (필수) | 전역 고유 이름. 예: `board-tfstate-<account id>` |
| `region` | `us-east-1` | decision-1 |
| `state_key` | `main/prod/terraform.tfstate` | `backend_config` 출력에 들어갈 main state 키 |
| `noncurrent_version_days` | `90` | 이전 state 버전 보관 일수 |

출력: `state_bucket`, `backend_config`(그대로 `infra/main/envs/prod.backend.hcl` 로 저장).
