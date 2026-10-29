# Terraform state 운영 (TASK-65#6, OPS-5)

state 는 `infra/bootstrap`이 만든 S3 버킷 하나에 스택별 키로 둔다. 잠금은 S3 native lockfile(`use_lockfile = true`, Terraform >= 1.10)이라
DynamoDB 테이블이 없고, 잠금 파일은 state 객체 옆의 `<키>.tflock`이다.

| 스택 | state | backend 설정 |
|---|---|---|
| `infra/bootstrap` | **로컬** `infra/bootstrap/terraform.tfstate` (git 에 올리지 않는다) | 없음 — 버킷을 만드는 스택이라 |
| `infra/main` (레거시, TASK-52 AC #5 에서 철거) | `s3://<버킷>/main/prod/terraform.tfstate` | `infra/main/envs/prod.backend.hcl` |
| `infra/platform` | `s3://<버킷>/platform/terraform.tfstate` | `infra/platform/envs/prod.backend.hcl` |

두 스택의 키가 달라 서로의 state·잠금을 건드리지 않는다. 아래 명령은 모두 대상 계정을 `aws sts get-caller-identity`로 확인한 뒤 돌린다.

## 잠금이 고착됐을 때 (force-unlock)

`terraform plan/apply`가 중간에 끊기면(노트북 잠자기, 네트워크, Ctrl-C 두 번) `<키>.tflock`이 남아 다음 실행이
`Error acquiring the state lock`으로 멈춘다.

1. **정말 아무도 돌리고 있지 않은지 먼저 확인한다.** 오류 메시지의 `Lock Info`(ID·Who·Created)를 보고, 다른 터미널·CI 에서 같은 스택을
   돌리는 중이면 끝날 때까지 기다린다. 돌고 있는 apply 의 잠금을 풀면 state 가 갈라진다.
2. 잠금 파일을 확인한다.

   ```bash
   aws s3 ls s3://<버킷>/platform/terraform.tfstate.tflock
   ```

3. 오류 메시지의 Lock ID 로 푼다(스택 디렉터리에서, backend 를 init 한 상태로).

   ```bash
   terraform -chdir=infra/platform force-unlock <LOCK_ID>
   ```

4. 바로 `terraform plan`을 돌려 state 가 실제와 맞는지 본다. 끊긴 apply 가 일부 리소스를 만들었으면 plan 에 드러난다.

`force-unlock`이 실패하면(잠금 파일이 깨진 경우) `aws s3 rm s3://<버킷>/<키>.tflock`으로 지운다 — 1번 확인을 마친 경우에만.

## state 백업·복구

### infra/main·infra/platform (S3)

버킷은 버전 관리가 켜져 있어(이전 버전 90일 보관) apply 마다 이전 state 가 남는다. 따로 백업하지 않아도 되지만, 큰 변경(모듈 이동·
`terraform state mv`·철거) 전에는 손으로 한 벌 받아 둔다.

```bash
terraform -chdir=infra/platform state pull > platform-$(date +%Y%m%d%H%M).tfstate   # 비밀값(Cognito client secret)이 들어 있다 — 암호화된 곳에만
```

복구 — 망가진 state 를 이전 버전으로 되돌린다:

```bash
aws s3api list-object-versions --bucket <버킷> --prefix platform/terraform.tfstate \
  --query 'Versions[].{id:VersionId,at:LastModified,latest:IsLatest}' --output table
aws s3api get-object --bucket <버킷> --key platform/terraform.tfstate --version-id <VERSION_ID> restored.tfstate
terraform -chdir=infra/platform state push restored.tfstate   # serial 이 더 낮으면 거부된다 — 확인 뒤 -force
terraform -chdir=infra/platform plan                          # 실제와 맞는지
```

### infra/bootstrap (로컬)

bootstrap state 는 버킷을 만든 사람의 디스크에만 있다. 잃어버리면 버킷 설정(수명 주기·정책)을 Terraform 으로 바꿀 수 없게 되므로
apply 직후 백업한다.

1. **백업** — apply 할 때마다 같은 버킷의 별도 키에 올린다(버전 관리로 이력이 남는다). 비밀값은 없지만 계정 정보가 있으니 퍼블릭 차단된 이 버킷에만 둔다.

   ```bash
   aws s3 cp infra/bootstrap/terraform.tfstate s3://<버킷>/bootstrap/terraform.tfstate
   ```

2. **복구 — 백업이 있으면** 받아서 제자리에 둔다.

   ```bash
   aws s3 cp s3://<버킷>/bootstrap/terraform.tfstate infra/bootstrap/terraform.tfstate
   terraform -chdir=infra/bootstrap plan -var "state_bucket_name=<버킷>"   # 바뀔 것이 없어야 한다
   ```

3. **복구 — 백업도 없으면** 빈 state 에 다시 가져온다(버킷 자체는 `prevent_destroy`라 남아 있다).

   ```bash
   cd infra/bootstrap && terraform init
   for r in aws_s3_bucket.state aws_s3_bucket_versioning.state aws_s3_bucket_server_side_encryption_configuration.state \
            aws_s3_bucket_public_access_block.state aws_s3_bucket_ownership_controls.state \
            aws_s3_bucket_lifecycle_configuration.state aws_s3_bucket_policy.state; do
     terraform import -var "state_bucket_name=<버킷>" "$r" <버킷>
   done
   terraform plan -var "state_bucket_name=<버킷>"
   ```

`infra/bootstrap/README.md`에 있는 대로 bootstrap state 자체를 `terraform init -migrate-state`로 이 버킷에 옮기는 방법도 있다.
그러면 백업 단계가 필요 없지만, 버킷을 지울 때 state 가 먼저 사라지는 순서 문제가 생겨 이 저장소는 로컬 + 백업을 쓴다.
