# TASK-65 AWS 실배포 플랫폼 스택(infra/platform, decision-11) 테스트. mock_provider 로 오프라인 — apply 하지 않는다.
# 실제 plan 은 TASK-52 에서 자격 증명이 있을 때 `terraform plan -var-file=envs/prod.tfvars` 로 한다.

mock_provider "aws" {}

override_data {
  target = module.k3s_node.data.aws_ami.rocky
  values = {
    id = "ami-0rocky98arm64000"
  }
}

override_data {
  target = module.k3s_node.data.aws_region.current
  values = {
    region = "us-east-1"
  }
}

override_data {
  target = module.k3s_node.data.aws_caller_identity.current
  values = {
    account_id = "123456789012"
  }
}

override_resource {
  target = module.k3s_node.aws_eip.this
  values = {
    public_ip = "3.1.2.3"
  }
}

variables {
  repo_commit       = "0123456789abcdef0123456789abcdef01234567"
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

# TASK-65#2 노드는 network 의 public subnet·AZ 에 t4g.large 로 붙고, 공개 주소는 EIP 하나
run "node_wiring" {
  command = apply

  assert {
    condition     = module.k3s_node.subnet_id == module.network.subnet_id && module.k3s_node.availability_zone == "us-east-1a"
    error_message = "노드는 network 의 public subnet"
  }
  assert {
    condition     = module.k3s_node.instance_type == "t4g.large"
    error_message = "기본 노드 타입 t4g.large(decision-11)"
  }
  assert {
    condition     = output.node_public_ip == "3.1.2.3" && output.node_instance_id == module.k3s_node.instance_id
    error_message = "출력: node_public_ip(EIP), node_instance_id(SSM 접속 대상)"
  }
}

# TASK-65#4 노드 부트스트랩: 고정 커밋·k3s 설치 자리를 노드로 넘기고, 배포 전 준비 확인 명령을 출력한다
run "node_bootstrap" {
  command = apply

  variables {
    k3s_install_cmd = "/opt/board-src/scripts/k3s-node.sh install"
  }

  assert {
    condition     = output.node_ready_check == "sudo /usr/local/sbin/board-node-ready"
    error_message = "출력: node_ready_check(OPS-10)"
  }
  assert {
    condition     = output.repo_commit == "0123456789abcdef0123456789abcdef01234567"
    error_message = "출력: 노드가 설치한 커밋"
  }
}
