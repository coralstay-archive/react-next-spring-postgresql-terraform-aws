# TASK-65#5 lb_node 모듈 테스트: HAProxy EC2(t4g.small) 가 80·443 을 TCP 로 k3s 노드에 넘긴다. 기본으로 꺼 둔다(루트 enable_lb_node). mock_provider(오프라인).

mock_provider "aws" {}

variables {
  name       = "board-test"
  vpc_id     = "vpc-123"
  subnet_id  = "subnet-123"
  ami_id     = "ami-0rocky98arm64000"
  backend_ip = "10.20.1.10"
}

# TASK-65#5 HAProxy 노드: t4g.small 온디맨드, IMDSv2, 루트 암호화, SSM 접속(22 없음), EIP
run "instance" {
  command = apply

  assert {
    condition     = aws_instance.this.instance_type == "t4g.small" && aws_instance.this.ami == "ami-0rocky98arm64000"
    error_message = "t4g.small, k3s 노드와 같은 Rocky AMI"
  }
  assert {
    condition     = length(aws_instance.this.instance_market_options) == 0
    error_message = "LB 는 온디맨드(스팟 회수로 입구가 닫히지 않게)"
  }
  assert {
    condition     = one(aws_instance.this.metadata_options).http_tokens == "required" && one(aws_instance.this.root_block_device).encrypted
    error_message = "IMDSv2 필수, 루트 암호화"
  }
  assert {
    condition = toset(flatten([for s in jsondecode(aws_iam_role_policy.ssm_agent.policy).Statement : s.Action])) == toset([
      "ssm:UpdateInstanceInformation",
      "ssmmessages:CreateControlChannel", "ssmmessages:CreateDataChannel", "ssmmessages:OpenControlChannel", "ssmmessages:OpenDataChannel",
      "ec2messages:AcknowledgeMessage", "ec2messages:DeleteMessage", "ec2messages:FailMessage", "ec2messages:GetEndpoint", "ec2messages:GetMessages", "ec2messages:SendReply",
    ])
    error_message = "SSM 접속은 에이전트 최소 권한만(SSMManagedInstanceCore 아님 — 파라미터 읽기 없음)"
  }
  assert {
    condition     = aws_eip.this.instance == aws_instance.this.id
    error_message = "LB 에 EIP"
  }
}

# TASK-65#5 SG: 80·443 IPv4·IPv6 만(22 없음)
run "security_group" {
  command = apply

  assert {
    condition = toset([for r in aws_vpc_security_group_ingress_rule.web : "${r.from_port}/${coalesce(r.cidr_ipv4, r.cidr_ipv6)}"]) == toset([
      "80/0.0.0.0/0", "80/::/0", "443/0.0.0.0/0", "443/::/0",
    ])
    error_message = "ingress 는 80·443 × IPv4·IPv6"
  }
}

# TASK-65#5 user_data: haproxy 설치(dnf 재시도), 80·443 을 TCP 모드로 backend_ip 에 넘긴다(TLS 는 노드의 Traefik·cert-manager)
run "haproxy_config" {
  command = apply

  assert {
    condition     = strcontains(aws_instance.this.user_data, "dnf -y install haproxy") && strcontains(aws_instance.this.user_data, "systemctl enable --now haproxy")
    error_message = "haproxy 설치·기동"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "mode tcp") && strcontains(aws_instance.this.user_data, "server node 10.20.1.10:443") && strcontains(aws_instance.this.user_data, "server node 10.20.1.10:80")
    error_message = "80·443 TCP 패스스루 → 노드 사설 IP"
  }
  assert {
    condition     = aws_instance.this.user_data_replace_on_change
    error_message = "user_data 가 바뀌면 LB 를 새로 만든다(OPS-2)"
  }
}
