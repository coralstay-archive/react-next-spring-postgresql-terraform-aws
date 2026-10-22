# 첫 배포 런북 (TASK-18)

빈 AWS 계정(us-east-1)에 처음 배포할 때 쓰는 순서다. 위에서부터 한 단계씩 진행하고, 단계마다 **확인** 항목을 통과한 뒤 다음 단계로 넘어간다.
스크립트와 Terraform 설명은 [README 인프라 절](../README.md#인프라-terraform-task-16)과 [배포 절](../README.md#배포-task-17)에 있다.
OAuth 콘솔 설정은 [oauth-setup.md](oauth-setup.md), SMS 설정은 [sms-setup.md](sms-setup.md)를 따른다.

> 비용이 생기는 단계는 **[비용]**으로 표시했다. 월 예상 비용은 [README 비용 절](../README.md#비용-us-east-1-월-대략)을 참고한다.
> `terraform apply`와 `make deploy`는 운영 계정을 바꾼다. 각 단계 전에 대상 계정과 리전을 확인한다.

## 0. 준비물

| 도구 | 확인 |
|---|---|
| aws CLI v2 + 관리자 권한 자격 증명(SSO 프로필 권장) | `aws --version`, `aws sts get-caller-identity` |
| Terraform ≥ 1.10 (S3 native lockfile) | `terraform version` |
| Docker + buildx (Apple Silicon은 arm64를 그대로 빌드하고, x86은 QEMU를 쓴다) | `docker buildx ls` |
| jq, Node 24, Java 25 | `jq --version`, `node -v`, `java -version` |
| Session Manager 플러그인(호스트 접속용, 선택) | `session-manager-plugin --version` |

```bash
export AWS_PROFILE=<프로필>          # SSO면 먼저 aws sso login
export AWS_REGION=us-east-1
aws sts get-caller-identity           # Account 가 배포할 계정인지 확인
make test                             # 커밋된 HEAD 가 초록인지 먼저 확인
```

## 1. state 버킷 (bootstrap, 계정당 1회) [비용: 거의 0]

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
terraform -chdir=infra/bootstrap init
terraform -chdir=infra/bootstrap apply -var "state_bucket_name=board-tfstate-$ACCOUNT_ID"
terraform -chdir=infra/bootstrap output -raw backend_config > infra/main/envs/prod.backend.hcl
```

**확인**: `infra/main/envs/prod.backend.hcl`에 `bucket = "board-tfstate-<account>"`와 `use_lockfile = true`가 있다.
`infra/bootstrap/terraform.tfstate`는 로컬에만 있다(gitignore). 잃어버리지 않게 보관한다.

## 2. tfvars 채우기

`infra/main/envs/prod.tfvars`에서 `CHANGE-ME`를 바꾼다.

| 변수 | 예 | 주의 |
|---|---|---|
| `web_bucket_name` | `board-prod-web-<account>` | S3 이름은 전역에서 고유해야 한다 |
| `uploads_bucket_name` | `board-prod-uploads-<account>` | 전역 고유 |
| `cognito_domain_prefix` | `board-prod-login-<account>` | 소문자·숫자·하이픈만 쓴다. `aws`·`amazon`·`cognito`는 넣을 수 없다 |
| `github_repository` | `owner/repo` | 처음엔 비워 둬도 된다(9단계에서 채워 다시 apply) |
| `domain_name` | `""` | 처음엔 `*.cloudfront.net`으로 시작한다(decision-9). 도메인으로 바꾸는 방법은 [oauth-setup.md](oauth-setup.md#cloudfrontnet-도메인-위험과-대안) |

**확인**: `grep -n CHANGE-ME infra/main/envs/prod.tfvars`가 아무것도 찾지 않는다.

## 3. main 스택 apply [비용 시작]

```bash
terraform -chdir=infra/main init -backend-config=envs/prod.backend.hcl
terraform -chdir=infra/main plan  -var-file=envs/prod.tfvars -out=prod.tfplan
# plan 검토: EC2 t4g.small 1, EBS 데이터 볼륨 1, EIP 1, CloudFront 1, S3 2(+state), ECR 1, Cognito User Pool 1, IAM, DLM, 로그 그룹
terraform -chdir=infra/main apply prod.tfplan
```

CloudFront 배포를 만드는 데 몇 분이 걸린다. **확인**:

```bash
make tf-outputs                                   # AWS_REGION, ECR_REPOSITORY_URL, WEB_BUCKET, DISTRIBUTION_ID, INSTANCE_ID ...
terraform -chdir=infra/main output -raw public_base_url    # https://dxxxx.cloudfront.net
terraform -chdir=infra/main output app_env
```

## 4. EC2 SSM 등록 확인

첫 부팅의 user_data(docker·compose 설치)와 SSM agent 등록에는 몇 분이 걸린다.

```bash
INSTANCE_ID=$(terraform -chdir=infra/main output -raw instance_id)
aws ssm describe-instance-information --filters "Key=InstanceIds,Values=$INSTANCE_ID" \
  --query 'InstanceInformationList[0].PingStatus' --output text      # Online 이 나올 때까지 기다린다
aws ssm start-session --target "$INSTANCE_ID"                         # (선택) 접속해서 확인
#   docker version && docker compose version && df -h /srv/pgdata && cat /var/log/cloud-init-output.log | tail
```

**확인**: `Online`이 나오고, `/srv/pgdata`가 데이터 EBS로 마운트돼 있다.

## 5. 시크릿 → SSM

```bash
cp deploy/secrets.example.txt deploy/secrets.local.txt   # gitignore 된다
#   ADMIN_EMAIL               여기서는 넣지 않는다 — 관리자가 가입하고 확인된 뒤 7번에서 넣고, 승격되면 지운다
#   GOOGLE_/GITHUB_/NAVER_/KAKAO_ CLIENT_ID·SECRET   — oauth-setup.md 순서로 받는다. 아직 없으면 비워 둔다(그 제공자만 꺼진다)
#   SMS_PROVIDER=aws, SMS_ORIGINATION_IDENTITY        — sms-setup.md. 아직 준비 전이면 아래 주의 참고
make put-secrets PUT_SECRETS_FLAGS=--generate-missing DRY_RUN=1   # 무엇을 넣을지 확인(시크릿 값은 ****)
make put-secrets PUT_SECRETS_FLAGS=--generate-missing
aws ssm get-parameters-by-path --path /board/prod --query 'Parameters[].Name' --output text
```

- `--generate-missing`은 SSM에 `DB_PASSWORD`·`JWT_SECRET`·`OTP_HASH_KEY`가 없을 때만 무작위 값을 만든다. 다시 돌려도 기존 값은 바꾸지 않는다.
- `DB_PASSWORD`는 postgres 데이터 디렉터리를 처음 초기화할 때만 쓰인다. 첫 배포 뒤에는 바꾸지 않는다.
- SMS가 아직 샌드박스여도 `SMS_PROVIDER=aws`로 둔다. 검증된 대상 번호로만 발송된다([sms-setup.md](sms-setup.md)). 운영 모드(`PUBLIC_BASE_URL`이 https)에서
  `console`·`memory`·빈 값이면 백엔드가 기동하지 않는다.

**확인**: `DB_PASSWORD`, `JWT_SECRET`, `OTP_HASH_KEY`, `PUBLIC_BASE_URL`, `COOKIE_SECURE`, `CLIENT_IP_HEADER`, `SMS_PROVIDER`, `UPLOADS_BUCKET`, `COGNITO_*`가 목록에 있다
(`SMS_PROVIDER=aws`는 terraform `app_env`가 넣는다. `PRODUCTION_MODE`는 없어도 된다 — render-env 가 `true`로 채운다).

## 6. 첫 배포 (`make deploy`) [ECR·S3 사용량]

```bash
git status --short                 # 비어 있어야 한다(build-backend 는 커밋 안 된 변경을 거부한다)
make deploy DRY_RUN=1              # 명령만 출력
make deploy                        # build-backend(arm64 → ECR) → deploy-backend(SSM send-command, 헬스 대기) → deploy-frontend(S3 + invalidation)
```

`deploy-backend`는 원격 출력에서 `==> healthy: <sha>`를 보여 주면 성공이다. 실패하면 api 로그 100줄이 출력되고, 처음 배포라 되돌릴 태그가 없어 그대로 실패한다.
CloudWatch `aws logs tail /board/prod --follow --log-stream-name-prefix api/`와 [README 문제 해결](../README.md#문제-해결)을 본다.

- **운영 안전 검사**: 운영 배포는 `PRODUCTION_MODE=true`(render-env 기본값)라 백엔드가 운영 모드로 기동하고, 안전하지 않은 설정이면 DB에 붙기 전에 멈춘다. 로그의
  `운영 모드(...)에서 안전하지 않은 설정이라 기동하지 않는다. 다음을 고친다:` 아래에 고칠 키가 모두 나온다 — 저장소에 공개된 `JWT_SECRET`·
  `OTP_HASH_KEY`(개발 기본값·example.env·E2E 값), `COOKIE_SECURE`≠true, `DB_PASSWORD`가 비었거나 `board`, `CLIENT_IP_HEADER` 비어 있음,
  `SMS_PROVIDER`≠aws. `put-secrets`로 고치고 다시 `make deploy-backend`한다.

### PostgreSQL 확장(pg_trgm) 권한

마이그레이션 V8이 게시판 검색용 `CREATE EXTENSION IF NOT EXISTS pg_trgm`을 실행한다. 기본 구성(EC2의 postgres 컨테이너, `POSTGRES_USER`가 슈퍼유저)은
따로 할 일이 없다. 다른 DB(RDS, 관리형 PostgreSQL, 권한을 줄인 앱 사용자)를 쓰면 다음을 확인한다.

- pg_trgm은 PostgreSQL 13+에서 trusted 확장이라 **현재 DB에 CREATE 권한**이 있는 사용자(DB 소유자, RDS 마스터 사용자)면 만들 수 있다.
  앱 DB 사용자(`DB_USERNAME`)에게 권한이 없으면 `GRANT CREATE ON DATABASE board TO <user>;`를 하거나, 권한 있는 계정으로 그 DB에서
  `CREATE EXTENSION IF NOT EXISTS pg_trgm;`을 먼저 실행한다(그러면 V8의 `IF NOT EXISTS`가 그냥 지나간다).
- 확장이 설치되지 않은 PostgreSQL이면 contrib 패키지를 설치한다(공식 이미지·RDS는 기본 포함).
- 실패하면 기동 실패 보고서가 `DB 마이그레이션 V8__post_search_trgm.sql 의 CREATE EXTENSION pg_trgm 이 실패해 기동하지 못했다: <DB 메시지>`와
  위 조치를 보여 준다. 조치 뒤 다시 기동하면 Flyway가 V8부터 이어서 적용한다.

## 7. 스모크 (AC#1)

```bash
BASE=$(terraform -chdir=infra/main output -raw public_base_url)
curl -fsS "$BASE/api/actuator/health"                   # {"status":"UP"} — AC#1
SMOKE_BASE_URL="$BASE" make smoke                        # health·HTML no-cache·_next/static immutable·XSRF 쿠키·/api/posts
```

`make smoke`(`scripts/test/smoke_test.sh`)는 읽기만 하므로 운영에 돌려도 안전하다. 5개 항목이 모두 `ok`여야 한다.

브라우저로 직접 확인한다.

1. `$BASE/`가 열리고, 새로 고쳐도 최신 HTML을 받는다.
2. 첫 관리자: 가입만으로는 ADMIN이 되지 않고, 기동 시 승격은 **ADMIN이 하나도 없을 때 한 번만** 한다(누가 먼저 그 이메일로 가입하거나,
   나중에 같은 이메일을 다시 차지해 관리자를 가로채지 못하게). 다음 순서를 지킨다.
   1. 관리자 본인이 `$BASE/signup/`에서 관리자 이메일로 가입한다(이때는 일반 사용자다). 이 시점에 `ADMIN_EMAIL`은 아직 SSM에 없다.
   2. 운영자가 그 계정이 관리자 본인 것인지 확인한다 — 가입한 사람에게 닉네임·가입 시각을 묻고, 모르는 가입이면 승격하지 않는다
      (다른 주소로 다시 가입하게 한다).
   3. 확인이 끝난 뒤에야 `ADMIN_EMAIL`을 넣고 백엔드를 다시 띄운다. 기동할 때 그 LOCAL 계정을 ADMIN으로 올리고
      로그에 `app.admin.email 의 LOCAL 계정(user=<id>)을 ADMIN 으로 올렸다`가 남는다.
      ```bash
      echo 'ADMIN_EMAIL=<확인된 관리자 이메일>' >> deploy/secrets.local.txt && make put-secrets && make deploy-backend
      ```
   4. 다시 로그인하면 헤더에 관리자 메뉴가 보이고 `/admin/`이 열린다.
   5. **반드시** `ADMIN_EMAIL`을 지운다(빈 값은 put-secrets 가 넣지 않으므로 SSM 파라미터를 직접 지운다). ADMIN이 있으면 부트스트랩은 건너뛰지만,
      남겨 두면 관리자를 모두 강등했을 때 다시 그 이메일이 승격 대상이 된다.
      ```bash
      sed -i.bak '/^ADMIN_EMAIL=/d' deploy/secrets.local.txt
      aws ssm delete-parameter --name /board/prod/ADMIN_EMAIL && make deploy-backend
      ```
3. 글을 쓰고 이미지를 첨부한다. 이미지 URL은 `/uploads/...`(같은 오리진)이다.
4. 휴대폰 로그인([sms-setup.md](sms-setup.md)의 샌드박스 검증 번호로)과 소셜 로그인([oauth-setup.md](oauth-setup.md)의 체크리스트)을 확인한다.

## 8. OAuth·SMS 외부 설정

- [oauth-setup.md](oauth-setup.md): 제공자 콘솔에 `${PUBLIC_BASE_URL}/api/login/oauth2/code/{id}`를 등록한다. id·secret을 `deploy/secrets.local.txt`에 넣고
  `make put-secrets` → `make deploy-backend`(시크릿은 배포할 때 다시 읽는다).
- [sms-setup.md](sms-setup.md): 샌드박스 해제, 한국 발신 등록, 지출 한도를 설정한다.

## 9. GitHub Actions 연결

```bash
# 1) prod.tfvars: github_repository = "owner/repo" → apply (ci_oidc 모듈이 켜진다)
terraform -chdir=infra/main plan -var-file=envs/prod.tfvars -out=prod.tfplan && terraform -chdir=infra/main apply prod.tfplan
terraform -chdir=infra/main output -raw ci_role_arn
# 2) 리포 Variables(시크릿 아님) — gh CLI 예시
gh variable set AWS_DEPLOY_ROLE_ARN --body "$(terraform -chdir=infra/main output -raw ci_role_arn)"
gh variable set AWS_REGION          --body us-east-1
gh variable set ECR_REPOSITORY_URL  --body "$(terraform -chdir=infra/main output -raw ecr_repository_url)"
gh variable set WEB_BUCKET          --body "$(terraform -chdir=infra/main output -raw web_bucket)"
gh variable set DISTRIBUTION_ID     --body "$(terraform -chdir=infra/main output -raw distribution_id)"
gh variable set INSTANCE_ID         --body "$(terraform -chdir=infra/main output -raw instance_id)"
```

- 계정에 GitHub OIDC provider가 이미 있으면 `existing_github_oidc_provider_arn`을 넣는다(provider는 계정당 하나다).
- 비공개 리포라면 `ubuntu-24.04-arm` 러너를 지금 플랜에서 쓸 수 있는지 확인한다.

## 10. push → deploy.yml 확인

```bash
git push origin main             # 또는 Actions → deploy → Run workflow (main)
gh run watch                     # test(ci.yml) → deploy(OIDC → build → deploy-backend → deploy-frontend)
SMOKE_BASE_URL="$BASE" make smoke
aws ssm start-session --target "$INSTANCE_ID"   # sudo cat /opt/board/current_tag → push 한 커밋 sha 12자리
```

**확인**: run이 초록이고, `current_tag`가 push한 커밋이며, 스모크를 통과한다.
`Not authorized to perform sts:AssumeRoleWithWebIdentity`가 나오면 `github_repository`, 브랜치(main), job에 environment를 붙이지 않았는지 확인한다.

## 11. 롤백 시험

**수동 롤백**(특정 태그로): 이전 커밋의 이미지가 ECR에 있어야 한다(최근 20개).

```bash
aws ecr describe-images --repository-name board-api --query 'sort_by(imageDetails,&imagePushedAt)[-5:].imageTags'
IMAGE_TAG=<이전 sha 12자리> ./scripts/tf-outputs.sh -- ./scripts/deploy-backend.sh     # ==> healthy: <이전 sha>
SMOKE_BASE_URL="$BASE" make smoke
IMAGE_TAG=<현재 sha> ./scripts/tf-outputs.sh -- ./scripts/deploy-backend.sh          # 원래대로
```

**자동 롤백**(헬스 실패 → 직전 태그): 뜨지 않는 이미지를 일부러 올려 본다.

```bash
ECR=$(terraform -chdir=infra/main output -raw ecr_repository_url)
aws ecr get-login-password | docker login --username AWS --password-stdin "${ECR%%/*}"
docker buildx build --platform linux/arm64 --provenance=false -t "$ECR:rollback-test" --push - <<'EOF'
FROM busybox
CMD ["false"]
EOF
IMAGE_TAG=rollback-test ./scripts/tf-outputs.sh -- ./scripts/deploy-backend.sh
# 기대: 약 240초 뒤 "health check failed" → "rolled back to <직전 sha>" 와 함께 종료 코드 1
SMOKE_BASE_URL="$BASE" make smoke                        # 직전 버전으로 계속 서비스 중
aws ecr batch-delete-image --repository-name board-api --image-ids imageTag=rollback-test
```

롤백은 같은 SSM 시크릿으로 직전 이미지를 다시 띄운다. 시크릿 값이 잘못돼 실패했다면 롤백도 실패한다. 그때는 시크릿부터 고친다.
Flyway 마이그레이션은 되돌리지 않는다.

## 12. 정리 (destroy 순서)

실습 환경을 지울 때의 순서다. **운영 데이터가 있으면 먼저 백업한다**(데이터 EBS는 최종 스냅샷을 남긴다).

1. `prod.tfvars`에 `cognito_deletion_protection = false`를 넣고 apply한다(User Pool 삭제 보호를 끈다).
2. 첨부를 지워도 되면 `uploads_force_destroy = true`로 apply한다. 아니면 첨부 버킷을 먼저 비운다. web 버킷은 `make deploy-frontend`가 채운 객체가 있으니 비운다:
   `aws s3 rm "s3://$(terraform -chdir=infra/main output -raw web_bucket)" --recursive`.
3. ECR 이미지가 있으면 리포를 지울 수 없다. 이미지를 지운다:
   `aws ecr list-images --repository-name board-api --query 'imageIds[*]' --output json > /tmp/ids.json && aws ecr batch-delete-image --repository-name board-api --image-ids file:///tmp/ids.json`.
4. `terraform -chdir=infra/main destroy -var-file=envs/prod.tfvars`. CloudFront 비활성화·삭제에 수십 분이 걸릴 수 있다.
5. Terraform이 만들지 않은 것은 손으로 지운다: SSM `/board/prod/*` 파라미터(`aws ssm get-parameters-by-path --path /board/prod --query 'Parameters[].Name' --output text | xargs -n10 aws ssm delete-parameters --names`),
   DLM 스냅샷과 최종 스냅샷(EC2 → Snapshots), End User Messaging의 발신 번호·등록(월 요금이 있다, [sms-setup.md](sms-setup.md)), CloudWatch 로그 그룹(남아 있으면).
6. state 버킷(bootstrap)은 `prevent_destroy`라 남는다. 지우려면 [infra/bootstrap/README.md](../infra/bootstrap/README.md)를 따른다.
7. GitHub 리포 Variables와 제공자 콘솔의 콜백 URL을 정리한다.

## 체크리스트 요약

- [ ] 0 자격 증명·도구, `make test` 초록
- [ ] 1 bootstrap → `prod.backend.hcl`
- [ ] 2 tfvars `CHANGE-ME` 없음
- [ ] 3 main apply, `make tf-outputs`
- [ ] 4 SSM `Online`
- [ ] 5 `make put-secrets --generate-missing`
- [ ] 6 `make deploy` → `healthy`
- [ ] 7 `curl $BASE/api/actuator/health` 200 (AC#1), `make smoke` 5/5
- [ ] 8 OAuth 콘솔 5종, SMS 설정
- [ ] 9 `github_repository` apply, 리포 Variables 6개
- [ ] 10 push → deploy.yml 초록 → smoke
- [ ] 11 수동·자동 롤백 시험
- [ ] 12 (정리할 때) destroy 순서
