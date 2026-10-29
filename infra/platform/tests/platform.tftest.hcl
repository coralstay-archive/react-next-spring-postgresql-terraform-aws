# TASK-65 AWS 실배포 플랫폼 스택(infra/platform, decision-11) 테스트. mock_provider 로 오프라인 — apply 하지 않는다.
# 실제 plan 은 TASK-52 에서 자격 증명이 있을 때 `terraform plan -var-file=envs/prod.tfvars` 로 한다.

mock_provider "aws" {
  # provider 가 ARN 형식을 검사하는 인자(DLM execution_role_arn)에 넘어가도록 ARN 모양 기본값을 준다
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/mock"
    }
  }
}

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

override_data {
  target = module.cognito.data.aws_region.current
  values = {
    region = "us-east-1"
  }
}

variables {
  cognito_domain_prefix = "board-test-login"
  repo_commit           = "0123456789abcdef0123456789abcdef01234567"
  name                  = "board-test"
  availability_zone     = "us-east-1a"
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

# TASK-65#5 토글 기본값: lb_node 꺼짐, 데이터 볼륨 스냅샷(DLM) 켜짐, Budgets 는 이메일이 없으면 만들지 않음, Cognito 는 기존 모듈 재사용
run "toggles_default" {
  command = apply

  assert {
    condition     = length(module.lb_node) == 0 && output.lb_public_ip == null
    error_message = "enable_lb_node 기본 false — Traefik 이 노드에서 바로 받는다(decision-11)"
  }
  assert {
    condition     = length(aws_dlm_lifecycle_policy.data) == 1
    error_message = "enable_snapshots 기본 true"
  }
  assert {
    condition     = one(aws_dlm_lifecycle_policy.data[0].policy_details).target_tags == tomap({ Snapshot = "board-test-data" })
    error_message = "DLM 대상은 k3s_node 데이터 볼륨 태그"
  }
  assert {
    condition     = one(one(one(aws_dlm_lifecycle_policy.data[0].policy_details).schedule).retain_rule).count == 7
    error_message = "스냅샷 7개 보관"
  }
  assert {
    condition     = length(aws_budgets_budget.monthly) == 0
    error_message = "budget_email 이 비면 Budgets 를 만들지 않는다"
  }
  assert {
    condition     = output.public_base_url == "https://3-1-2-3.sslip.io"
    error_message = "도메인 전에는 EIP 의 sslip.io 이름(decision-11)"
  }
  assert {
    condition     = toset(module.cognito.callback_urls) == toset(["https://3-1-2-3.sslip.io/api/login/oauth2/code/cognito"])
    error_message = "Cognito 콜백은 공개 주소에서(cognito 모듈 재사용)"
  }
}

# TASK-65#5 토글을 켜면: lb_node(HAProxy → 노드 사설 IP), Budgets(월 한도·이메일), 스냅샷 끄기, 사용자 도메인·추가 콜백
run "toggles_on" {
  command = apply

  variables {
    enable_lb_node              = true
    enable_snapshots            = false
    budget_email                = "ops@example.com"
    budget_monthly_usd          = 35
    domain_name                 = "board.example.com"
    cognito_extra_callback_urls = ["http://localhost:3000/api/login/oauth2/code/cognito"]
  }

  assert {
    condition     = length(module.lb_node) == 1 && module.lb_node[0].backend_ip == module.k3s_node.private_ip
    error_message = "lb_node 는 k3s 노드 사설 IP 로 넘긴다"
  }
  assert {
    condition     = output.lb_public_ip == module.lb_node[0].public_ip
    error_message = "출력: lb_public_ip"
  }
  assert {
    condition     = length(aws_dlm_lifecycle_policy.data) == 0
    error_message = "enable_snapshots=false 면 DLM 없음"
  }
  assert {
    condition = (
      length(aws_budgets_budget.monthly) == 1 && aws_budgets_budget.monthly[0].limit_amount == "35" && aws_budgets_budget.monthly[0].limit_unit == "USD"
      && aws_budgets_budget.monthly[0].time_unit == "MONTHLY" && aws_budgets_budget.monthly[0].budget_type == "COST"
    )
    error_message = "월 비용 예산 35 USD"
  }
  assert {
    condition = alltrue([
      for n in aws_budgets_budget.monthly[0].notification : contains(n.subscriber_email_addresses, "ops@example.com")
    ]) && toset([for n in aws_budgets_budget.monthly[0].notification : n.notification_type]) == toset(["ACTUAL", "FORECASTED"])
    error_message = "실제·예측 초과 알림을 이메일로"
  }
  assert {
    condition = output.public_base_url == "https://board.example.com" && toset(module.cognito.callback_urls) == toset([
      "https://board.example.com/api/login/oauth2/code/cognito", "http://localhost:3000/api/login/oauth2/code/cognito",
    ])
    error_message = "사용자 도메인 + 추가 콜백"
  }
}

# TASK-65#6 TASK-65#B3 관리형 서비스 없음: 스택이 만드는 EC2 는 k3s 노드 하나뿐이고 LB 자리는 비어 있다. lb_node 를 켜면 HAProxy EC2 가 생긴다.
# 리소스 종류 전체(NAT·ALB/NLB·CloudFront·RDS·MSK·ECR·CloudWatch 알람이 없음)는 scripts/test/platform_test.sh 가 허용 목록으로 본다
run "b3_no_lb_by_default" {
  command = plan

  assert {
    condition     = length(module.lb_node) == 0
    error_message = "B3 기본 구성에는 LB(관리형 ALB/NLB 도, HAProxy 도) 없음"
  }
  assert {
    condition     = output.managed_services == toset(["cognito", "ebs-snapshots", "ssm"])
    error_message = "B3 쓰는 관리형은 예외 목록(Cognito·SSM·EBS 스냅샷, 이메일이 있으면 Budgets)뿐 — SMS 는 앱 권한이라 TASK-52"
  }
}

run "b3_lb_node_on" {
  command = plan

  variables {
    enable_lb_node = true
  }

  assert {
    condition     = length(module.lb_node) == 1
    error_message = "lb_node 를 켜면 HAProxy LB 노드가 생긴다"
  }
  assert {
    condition     = output.managed_services == toset(["cognito", "ebs-snapshots", "ssm"])
    error_message = "lb_node 는 EC2 라 관리형 목록이 그대로"
  }
}
