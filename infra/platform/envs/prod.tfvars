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
