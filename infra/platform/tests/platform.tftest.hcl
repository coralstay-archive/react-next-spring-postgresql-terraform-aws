# TASK-65 AWS 실배포 플랫폼 스택(infra/platform, decision-11) 테스트. mock_provider 로 오프라인 — apply 하지 않는다.
# 실제 plan 은 TASK-52 에서 자격 증명이 있을 때 `terraform plan -var-file=envs/prod.tfvars` 로 한다.

mock_provider "aws" {}

variables {
  name              = "board-test"
  availability_zone = "us-east-1a"
}

# TASK-65#1 network 는 infra/main/modules/network 를 그대로 쓴다: VPC 1개 + public subnet 1개(AZ 변수), NAT 없음
run "network_reuse" {
  command = apply

  assert {
    condition     = module.network.availability_zone == "us-east-1a"
    error_message = "network 모듈의 public subnet 은 var.availability_zone"
  }
  assert {
    condition     = output.vpc_id == module.network.vpc_id && output.subnet_id == module.network.subnet_id
    error_message = "출력: vpc_id, subnet_id"
  }
  assert {
    condition     = output.region == "us-east-1"
    error_message = "리전 us-east-1(decision-11)"
  }
}
