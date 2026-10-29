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

override_resource {
  target = aws_ebs_volume.data
  values = {
    id = "vol-0abc123def4567890"
  }
}

variables {
  repo_commit       = "0123456789abcdef0123456789abcdef01234567"
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
    condition     = one(aws_instance.this.metadata_options).http_put_response_hop_limit == 2
    error_message = "hop 2: External Secrets(파드)가 노드 자격 증명으로 /board/* 를 읽는다 — 앱 네임스페이스는 NetworkPolicy 로 IMDS 차단(TASK-52)"
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

# TASK-65#2 IAM 최소 권한: AmazonSSMManagedInstanceCore 를 붙이지 않는다(ssm:GetParameter* 가 모든 파라미터에 열린다).
# 대신 SSM 에이전트(Session Manager·Run Command)에 필요한 동작만 + /board/* 파라미터 읽기 + 그 파라미터의 복호화만
run "iam" {
  command = apply

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.ssm_agent.policy).Statement :
      toset(s.Action) == toset([
        "ssm:UpdateInstanceInformation",
        "ssmmessages:CreateControlChannel", "ssmmessages:CreateDataChannel", "ssmmessages:OpenControlChannel", "ssmmessages:OpenDataChannel",
        "ec2messages:AcknowledgeMessage", "ec2messages:DeleteMessage", "ec2messages:FailMessage", "ec2messages:GetEndpoint", "ec2messages:GetMessages", "ec2messages:SendReply",
      ])
    ]) && length(jsondecode(aws_iam_role_policy.ssm_agent.policy).Statement) == 1
    error_message = "SSM 에이전트 정책은 Session Manager(ssmmessages)·Run Command(ec2messages)·UpdateInstanceInformation 만"
  }
  assert {
    condition = alltrue([
      for s in concat(jsondecode(aws_iam_role_policy.ssm_agent.policy).Statement, jsondecode(aws_iam_role_policy.params.policy).Statement) :
      alltrue([for a in flatten([s.Action]) : !strcontains(a, "*")])
    ])
    error_message = "와일드카드 Action 없음"
  }
  assert {
    condition = alltrue([
      for s in concat(jsondecode(aws_iam_role_policy.ssm_agent.policy).Statement, jsondecode(aws_iam_role_policy.params.policy).Statement) :
      !anytrue([for a in flatten([s.Action]) : startswith(a, "ssm:GetParameter")]) || !contains(flatten([s.Resource]), "*")
    ])
    error_message = "ssm:GetParameter* 는 * 리소스에 주지 않는다(/board/* 문장에만)"
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
      && s.Condition.StringLike["kms:EncryptionContext:PARAMETER_ARN"] == "arn:aws:ssm:us-east-1:123456789012:parameter/board/*"
    ])
    error_message = "SecureString 복호화는 SSM 경유 + /board/* 파라미터의 암호화 컨텍스트로만"
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

# TASK-65#3 데이터 볼륨: 노드와 같은 AZ 의 gp3 암호화 별도 볼륨, /dev/sdf 로 연결, 지울 때 최종 스냅샷, 스냅샷 대상 태그
run "data_volume" {
  command = apply

  assert {
    condition     = aws_ebs_volume.data.type == "gp3" && aws_ebs_volume.data.encrypted && aws_ebs_volume.data.size == 30
    error_message = "gp3 30GB 암호화(k3s data-dir 의 containerd 이미지도 /data)"
  }
  assert {
    condition     = aws_ebs_volume.data.availability_zone == aws_instance.this.availability_zone
    error_message = "노드와 같은 AZ"
  }
  assert {
    condition     = aws_ebs_volume.data.final_snapshot
    error_message = "볼륨을 지울 때 최종 스냅샷"
  }
  assert {
    condition = (
      aws_volume_attachment.data.device_name == "/dev/sdf" && aws_volume_attachment.data.volume_id == aws_ebs_volume.data.id
      && aws_volume_attachment.data.instance_id == aws_instance.this.id && aws_volume_attachment.data.stop_instance_before_detaching
    )
    error_message = "/dev/sdf 로 이 노드에 연결, 분리 전에 노드를 멈춘다"
  }
  assert {
    condition     = aws_ebs_volume.data.tags["Snapshot"] == "board-test-data" && output.snapshot_tag["Snapshot"] == "board-test-data"
    error_message = "스냅샷(DLM) 대상 태그 Snapshot=<name>-data"
  }
  assert {
    condition     = output.data_volume_id == aws_ebs_volume.data.id && output.data_mount_path == "/data"
    error_message = "출력: data_volume_id, data_mount_path"
  }
}

# TASK-65#3 이미 있는 볼륨은 data_snapshot_id 를 나중에 바꿔도 그대로다(ignore_changes — 스냅샷으로 덮어쓰지 않는다)
run "data_snapshot_id_later_ignored" {
  command = apply

  variables {
    data_snapshot_id = "snap-0123456789abcdef0"
  }

  assert {
    condition     = aws_ebs_volume.data.snapshot_id != "snap-0123456789abcdef0" && aws_ebs_volume.data.id == "vol-0abc123def4567890"
    error_message = "기존 볼륨을 갈아엎지 않는다(복원은 docs/terraform-state.md 절차로 -replace)"
  }
}

# TASK-65#3 data_snapshot_id 를 주고 처음 만들면 그 스냅샷에서 볼륨을 만든다(새 state — 복원 리허설은 TASK-52)
run "data_volume_from_snapshot" {
  command   = apply
  state_key = "from_snapshot"

  variables {
    data_snapshot_id = "snap-0123456789abcdef0"
  }

  assert {
    condition     = aws_ebs_volume.data.snapshot_id == "snap-0123456789abcdef0"
    error_message = "스냅샷에서 복원"
  }
}

# TASK-65#3 노드 이름은 변수로 바꿀 수 있다(바꾸면 기존 PV 가 고아가 된다 — README)
run "node_name" {
  command = plan

  variables {
    node_name = "board-node-2"
  }

  assert {
    condition     = strcontains(aws_instance.this.user_data, "node-name: board-node-2")
    error_message = "node_name 을 k3s 설정에"
  }
}

# TASK-65#3 data_snapshot_id 형식 검사
run "data_snapshot_id_invalid" {
  command = plan

  variables {
    data_snapshot_id = "vol-0123"
  }

  expect_failures = [var.data_snapshot_id]
}

# TASK-65#3 TASK-65#B2 user_data: 데이터 볼륨 스크립트(board-data-volume.sh)를 볼륨 ID·마운트 경로·지킬 서비스로 부른다.
# 포맷 조건(blkid 2)·마운트 차단(RequiresMountsFor)의 동작은 scripts/test/platform_test.sh 가 가짜 명령으로 본다
run "user_data_data_volume" {
  command = apply

  assert {
    condition     = strcontains(aws_instance.this.user_data, base64encode(file("files/data-volume.sh")))
    error_message = "files/data-volume.sh 를 그대로(base64) 싣는다"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "/usr/local/sbin/board-data-volume.sh vol-0abc123def4567890 /data k3s.service")
    error_message = "볼륨 ID·/data·k3s.service 로 부른다"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "data-dir: /data/k3s") && strcontains(aws_instance.this.user_data, "default-local-storage-path: /data/local-path")
    error_message = "k3s 데이터·PV(local-path) 디렉터리는 데이터 볼륨 위"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "node-name: board-node")
    error_message = "노드를 새로 만들어도 k3s 노드 이름이 같다 — local-path PV 의 nodeAffinity·/data/k3s 의 노드 등록이 이어진다"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "retry 3 dnf -y install policycoreutils-python-utils")
    error_message = "데이터 볼륨 스크립트의 semanage(SELinux 레이블)를 먼저 설치"
  }
  assert {
    condition     = !strcontains(aws_instance.this.user_data, "mkfs")
    error_message = "포맷은 스크립트 안에서만(blkid 종료 코드 2 일 때) — user_data 에 직접 mkfs 를 쓰지 않는다"
  }
}

# TASK-65#4 user_data: 저장소 고정 커밋의 deploy/rocky/install.sh --profile server 를 돌린다(dnf·git·설치 재시도 3회 — OPS-10).
# 노드 준비 확인 스크립트(board-node-ready: cloud-init status --wait + 끝 표식)를 먼저 깔고, 마지막에 끝 표식을 남긴다
run "user_data_install" {
  command = apply

  assert {
    condition     = strcontains(aws_instance.this.user_data, "retry 3 dnf -y install git")
    error_message = "dnf 재시도(OPS-10)"
  }
  assert {
    condition = (
      strcontains(aws_instance.this.user_data, "remote add origin https://github.com/coralstay/react-next-spring-postgresql-terraform-aws.git")
      && strcontains(aws_instance.this.user_data, "fetch -q --depth 1 origin 0123456789abcdef0123456789abcdef01234567")
      && strcontains(aws_instance.this.user_data, "checkout -q FETCH_HEAD")
    )
    error_message = "저장소를 고정 커밋으로 받는다(브랜치가 움직여도 노드가 바뀌지 않게)"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "retry 3 /opt/board-src/deploy/rocky/install.sh --profile server --user rocky")
    error_message = "install.sh --profile server(사용자 rocky), 재시도 3회(다시 돌려도 되는 스크립트)"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, base64encode(file("files/node-ready.sh"))) && strcontains(aws_instance.this.user_data, "/usr/local/sbin/board-node-ready")
    error_message = "준비 확인 스크립트(files/node-ready.sh)를 /usr/local/sbin/board-node-ready 로"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "/var/lib/board-node/bootstrap.done")
    error_message = "끝까지 돌았다는 표식"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "set -euo pipefail")
    error_message = "한 단계라도 실패하면 멈추고 cloud-init 이 error 가 된다"
  }
  assert {
    condition     = length(aws_instance.this.user_data) < 16384
    error_message = "EC2 user_data 16KB 한도"
  }
  assert {
    condition     = output.ready_check_command == "sudo /usr/local/sbin/board-node-ready"
    error_message = "출력: 배포 전에 반드시 돌릴 준비 확인 명령"
  }
}

# TASK-65#4 user_data 변경 정책(OPS-2): 바뀌면 노드를 새로 만든다(노드는 가축 — 데이터는 별도 볼륨). stop/start 로 넘어가 스크립트가 다시 안 도는 일을 막는다
run "user_data_replace_policy" {
  command = plan

  assert {
    condition     = aws_instance.this.user_data_replace_on_change
    error_message = "user_data_replace_on_change = true"
  }
}

# TASK-65#4 k3s 설치 자리: 기본은 비워 두고(TASK-36 스크립트), k3s_install_cmd 를 주면 install.sh 다음에 부른다
run "k3s_install_hook_default" {
  command = apply

  assert {
    condition     = strcontains(aws_instance.this.user_data, "k3s_install_cmd 없음") && !strcontains(aws_instance.this.user_data, "get.k3s.io")
    error_message = "기본은 k3s 를 설치하지 않는다(TASK-36 스크립트 자리)"
  }
}

run "k3s_install_hook_set" {
  command = apply

  variables {
    k3s_install_cmd = "/opt/board-src/scripts/k3s-node.sh install"
  }

  assert {
    condition     = strcontains(aws_instance.this.user_data, "/opt/board-src/scripts/k3s-node.sh install") && !strcontains(aws_instance.this.user_data, "k3s_install_cmd 없음")
    error_message = "k3s_install_cmd 를 부른다"
  }
}

# TASK-65#4 repo_commit 은 40자리 커밋 SHA(브랜치·태그 이름 불가)
run "repo_commit_invalid" {
  command = plan

  variables {
    repo_commit = "main"
  }

  expect_failures = [var.repo_commit]
}
