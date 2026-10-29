# infra/platform prod 변수(TASK-65). apply 는 TASK-52 에서. 사용:
#   terraform -chdir=infra/platform init -backend-config=envs/prod.backend.hcl
#   terraform -chdir=infra/platform plan -var-file=envs/prod.tfvars

region             = "us-east-1"
environment        = "prod"
name               = "board-platform"
availability_zone  = "us-east-1a"
node_instance_type = "t4g.large"

# 데이터 볼륨. 스냅샷에서 복원하려면 data_snapshot_id = "snap-…"(TASK-52 리허설)
data_volume_size = 20

# 노드가 deploy/rocky/install.sh --profile server 를 받을 커밋(git rev-parse HEAD, 40자리). 바꾸면 노드를 새로 만든다
repo_commit = "CHANGE-ME"
# k3s 설치(TASK-36 스크립트). 비우면 설치하지 않는다
k3s_install_cmd = ""

# 도메인 전에는 EIP 의 sslip.io 이름(decision-11). 도메인은 TASK-52 에서
domain_name = ""

# 토글(decision-11): HAProxy LB 노드는 기본 꺼 둔다. 데이터 볼륨 스냅샷은 켠다
enable_lb_node   = false
enable_snapshots = true

# AWS Budgets 월 알림. 이메일을 넣어야 만들어진다
budget_monthly_usd = 30
budget_email       = ""

cognito_domain_prefix       = "board-platform-login-change-me"
cognito_deletion_protection = true
