# TASK-65 k3s_node 모듈 테스트: Rocky 9.8 arm64 t4g.large 스팟 노드 + EIP + SG + IAM (+ 데이터 볼륨·user_data). mock_provider(오프라인).

mock_provider "aws" {}

override_data {
  target = data.aws_ami.rocky
  values = {
    id = "ami-0rocky98arm64000"
  }
}

override_data {
  target = data.aws_region.current
  values = {
    region = "us-east-1"
  }
}

override_data {
  target = data.aws_caller_identity.current
  values = {
    account_id = "123456789012"
  }
}

variables {
  name              = "board-test"
  vpc_id            = "vpc-123"
  subnet_id         = "subnet-123"
  availability_zone = "us-east-1a"
}

# TASK-65#2 AMI: Rocky 공식 계정(792107900819)의 Rocky 9.8 arm64 최신 이미지
run "ami" {
  command = apply

  assert {
    condition     = toset(data.aws_ami.rocky.owners) == toset(["792107900819"]) && data.aws_ami.rocky.most_recent
    error_message = "Rocky Enterprise Software Foundation 계정의 최신 AMI"
  }
  assert {
    condition = anytrue([
      for f in data.aws_ami.rocky.filter : f.name == "name" && contains(f.values, "Rocky-9-EC2-Base-9.8-*.aarch64")
    ])
    error_message = "이름 필터 Rocky-9-EC2-Base-9.8-*.aarch64"
  }
  assert {
    condition = anytrue([
      for f in data.aws_ami.rocky.filter : f.name == "architecture" && f.values == toset(["arm64"])
    ])
    error_message = "arm64(t4g)"
  }
  assert {
    condition     = aws_instance.this.ami == "ami-0rocky98arm64000"
    error_message = "조회한 AMI 로 노드를 띄운다"
  }
}

# TASK-65#2 노드: t4g.large 스팟(persistent, 회수 시 stop — 데이터·EIP 유지), IMDSv2 필수, 루트 gp3 암호화
run "instance" {
  command = apply

  assert {
    condition     = aws_instance.this.instance_type == "t4g.large"
    error_message = "t4g.large(8GB, decision-11)"
  }
  assert {
    condition     = one(aws_instance.this.instance_market_options).market_type == "spot"
    error_message = "스팟"
  }
  assert {
    condition = (
      one(one(aws_instance.this.instance_market_options).spot_options).spot_instance_type == "persistent"
      && one(one(aws_instance.this.instance_market_options).spot_options).instance_interruption_behavior == "stop"
    )
    error_message = "persistent 스팟, 회수 시 stop(terminate 아님)"
  }
  assert {
    condition     = one(aws_instance.this.metadata_options).http_tokens == "required" && one(aws_instance.this.metadata_options).http_endpoint == "enabled"
    error_message = "IMDSv2 필수"
  }
  assert {
    condition     = one(aws_instance.this.root_block_device).encrypted && one(aws_instance.this.root_block_device).volume_type == "gp3"
    error_message = "루트 볼륨 gp3 + 암호화"
  }
  assert {
    condition     = aws_instance.this.subnet_id == "subnet-123" && aws_instance.this.availability_zone == "us-east-1a"
    error_message = "지정한 subnet·AZ"
  }
  assert {
    condition     = aws_instance.this.iam_instance_profile == aws_iam_instance_profile.this.name
    error_message = "인스턴스 프로파일 연결"
  }
  assert {
    condition     = toset(aws_instance.this.vpc_security_group_ids) == toset([aws_security_group.this.id])
    error_message = "노드는 이 SG 만"
  }
}

# TASK-65#2 EIP 1개(공인 IPv4 1개 — decision-11 비용)
run "eip" {
  command = apply

  assert {
    condition     = aws_eip.this.instance == aws_instance.this.id && aws_eip.this.domain == "vpc"
    error_message = "EIP 를 노드에 연결"
  }
  assert {
    condition     = output.public_ip == aws_eip.this.public_ip && output.instance_id == aws_instance.this.id
    error_message = "출력: public_ip, instance_id"
  }
}

# TASK-65#2 TASK-65#B1 SG: 80·443 만 IPv4·IPv6 로 연다. 22 는 열지 않는다(접속은 SSM Session Manager)
run "security_group" {
  command = apply

  assert {
    condition = toset([for r in aws_vpc_security_group_ingress_rule.web : "${r.ip_protocol}/${r.from_port}-${r.to_port}/${coalesce(r.cidr_ipv4, r.cidr_ipv6)}"]) == toset([
      "tcp/80-80/0.0.0.0/0", "tcp/80-80/::/0", "tcp/443-443/0.0.0.0/0", "tcp/443-443/::/0",
    ])
    error_message = "ingress 는 80·443/tcp × IPv4·IPv6 네 개뿐"
  }
  assert {
    condition = alltrue([
      for r in aws_vpc_security_group_ingress_rule.web : !(r.from_port <= 22 && r.to_port >= 22) && r.ip_protocol != "-1"
    ])
    error_message = "B1 22 번 포트(또는 모든 포트)를 여는 규칙이 없다"
  }
  assert {
    condition     = length(aws_security_group.this.ingress) == 0
    error_message = "B1 SG 인라인 ingress(22 등) 없음 — 규칙은 별도 리소스뿐"
  }
  assert {
    condition     = alltrue([for r in aws_vpc_security_group_ingress_rule.web : r.security_group_id == aws_security_group.this.id])
    error_message = "규칙은 노드 SG 에"
  }
}

# TASK-65#2 IAM: SSM Core(Session Manager) + /board/* 파라미터 읽기(SecureString 복호화는 SSM 경유로만). 그 밖의 권한 없음
run "iam" {
  command = apply

  assert {
    condition     = aws_iam_role_policy_attachment.ssm_core.policy_arn == "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
    error_message = "SSM Session Manager·Run Command 용 managed policy"
  }
  assert {
    condition     = jsondecode(aws_iam_role.this.assume_role_policy).Statement[0].Principal.Service == "ec2.amazonaws.com"
    error_message = "EC2 가 assume"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.params.policy).Statement :
      toset(s.Action) == toset(["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"])
      && toset(s.Resource) == toset(["arn:aws:ssm:us-east-1:123456789012:parameter/board", "arn:aws:ssm:us-east-1:123456789012:parameter/board/*"])
    ])
    error_message = "SSM 파라미터 읽기는 /board/* 만"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.params.policy).Statement :
      s.Action == ["kms:Decrypt"] && s.Condition.StringEquals["kms:ViaService"] == "ssm.us-east-1.amazonaws.com"
    ])
    error_message = "SecureString 복호화는 SSM 경유로만"
  }
  assert {
    condition     = length(jsondecode(aws_iam_role_policy.params.policy).Statement) == 2
    error_message = "인라인 정책은 파라미터 읽기·복호화 두 문장뿐"
  }
  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.params.policy).Statement : !contains(flatten([s.Action]), "*")
    ])
    error_message = "와일드카드 Action 금지"
  }
}
