# infra/platform prod 변수(TASK-65). apply 는 TASK-52 에서. 사용:
#   terraform -chdir=infra/platform init -backend-config=envs/prod.backend.hcl
#   terraform -chdir=infra/platform plan -var-file=envs/prod.tfvars

region             = "us-east-1"
environment        = "prod"
name               = "board-platform"
availability_zone  = "us-east-1a"
node_instance_type = "t4g.large"
