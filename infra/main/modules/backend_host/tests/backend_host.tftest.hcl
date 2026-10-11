# TASK-16#2 backend_host 모듈 테스트: EC2(t4g.small, AL2023 arm64, IMDSv2) + 데이터 EBS + EIP + DLM + SG + IAM. mock_provider(오프라인).

mock_provider "aws" {
  # provider 가 ARN 형식을 검사하는 인자(DLM execution_role_arn 등)에 넘어가도록 ARN 모양 기본값을 준다
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/mock"
    }
  }
}

override_data {
  target = data.aws_ssm_parameter.ami
  values = {
    value          = "ami-0123456789abcdef0"
    insecure_value = "ami-0123456789abcdef0"
  }
}

override_data {
  target = data.aws_ec2_managed_prefix_list.cloudfront
  values = {
    id = "pl-3b927c52"
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

override_resource {
  target = aws_cloudwatch_log_group.app
  values = {
    arn = "arn:aws:logs:us-east-1:123456789012:log-group:/board/test"
  }
}

variables {
  name               = "board-test"
  vpc_id             = "vpc-123"
  subnet_id          = "subnet-123"
  availability_zone  = "us-east-1a"
  ecr_repository_arn = "arn:aws:ecr:us-east-1:123456789012:repository/board-api"
  log_group_name     = "/board/test"
  ssm_parameter_path = "/board/test"
}

# TASK-16#2 EC2: t4g.small, SSM 공개 파라미터의 AL2023 arm64 AMI, IMDSv2 필수(컨테이너도 IMDS 를 쓰도록 hop 2), 루트 암호화
run "instance" {
  command = apply

  assert {
    condition     = aws_instance.this.instance_type == "t4g.small"
    error_message = "t4g.small(decision-1)"
  }
  assert {
    condition     = data.aws_ssm_parameter.ami.name == "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
    error_message = "AMI 는 AL2023 arm64 SSM 공개 파라미터"
  }
  assert {
    condition     = aws_instance.this.ami == "ami-0123456789abcdef0"
    error_message = "SSM 파라미터 값의 AMI 를 쓴다"
  }
  assert {
    condition     = one(aws_instance.this.metadata_options).http_tokens == "required"
    error_message = "IMDSv2 필수"
  }
  assert {
    condition     = one(aws_instance.this.metadata_options).http_put_response_hop_limit == 2
    error_message = "도커 컨테이너(브리지 네트워크)의 SDK 가 인스턴스 프로파일을 쓰려면 hop limit 2"
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
}

# TASK-16#2 데이터 EBS: gp3 20GB 암호화, 인스턴스와 같은 AZ, /dev/sdf 로 붙이고, DLM 대상 태그, 삭제 시 최종 스냅샷
run "data_volume" {
  command = apply

  assert {
    condition     = aws_ebs_volume.data.type == "gp3" && aws_ebs_volume.data.size == 20 && aws_ebs_volume.data.encrypted
    error_message = "gp3 20GB 암호화"
  }
  assert {
    condition     = aws_ebs_volume.data.availability_zone == aws_instance.this.availability_zone
    error_message = "인스턴스와 같은 AZ"
  }
  assert {
    condition     = aws_ebs_volume.data.final_snapshot
    error_message = "volume 을 지울 때 최종 스냅샷"
  }
  assert {
    condition     = aws_volume_attachment.data.device_name == "/dev/sdf" && aws_volume_attachment.data.volume_id == aws_ebs_volume.data.id && aws_volume_attachment.data.instance_id == aws_instance.this.id
    error_message = "/dev/sdf 로 이 인스턴스에 연결"
  }
  assert {
    condition     = aws_volume_attachment.data.stop_instance_before_detaching
    error_message = "분리 전에 인스턴스를 멈춰 파일시스템을 안전하게 내린다"
  }
  assert {
    condition     = aws_ebs_volume.data.tags["Snapshot"] == "board-test-pgdata"
    error_message = "DLM 대상 태그 Snapshot=<name>-pgdata"
  }
}

# TASK-16#2 user_data: 비어 있을 때만 포맷, UUID 로 fstab(nofail), /srv/pgdata 마운트, docker + compose plugin, awslogs 기본 리전, /opt/board
run "user_data" {
  command = apply

  assert {
    condition     = strcontains(aws_instance.this.user_data, "nvme-Amazon_Elastic_Block_Store_vol0abc123def4567890")
    error_message = "NVMe 장치를 볼륨 ID 로 찾는다(/dev/sdf 는 nitro 에서 nvmeXn1 로 보인다)"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "blkid") && strcontains(aws_instance.this.user_data, "mkfs -t xfs")
    error_message = "파일시스템이 없을 때만(blkid) mkfs"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "UUID=") && strcontains(aws_instance.this.user_data, "/srv/pgdata xfs defaults,nofail")
    error_message = "fstab 은 UUID + nofail 로 /srv/pgdata"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "dnf install -y docker") && strcontains(aws_instance.this.user_data, "systemctl enable --now docker")
    error_message = "docker 설치·활성화"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "cli-plugins/docker-compose") && strcontains(aws_instance.this.user_data, "docker-compose-linux-aarch64")
    error_message = "compose plugin(arm64)"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "\"awslogs-region\": \"us-east-1\"") && strcontains(aws_instance.this.user_data, "\"awslogs-group\": \"/board/test\"")
    error_message = "docker 기본 로그 드라이버 awslogs(리전·로그 그룹)"
  }
  assert {
    condition     = strcontains(aws_instance.this.user_data, "mkdir -p /opt/board")
    error_message = "배포 디렉터리 /opt/board"
  }
}

# TASK-16#2 EIP: 인스턴스에 연결, public DNS 출력(CloudFront /api/* origin)
run "eip" {
  command = apply

  assert {
    condition     = aws_eip.this.instance == aws_instance.this.id && aws_eip.this.domain == "vpc"
    error_message = "EIP 를 인스턴스에 연결"
  }
  assert {
    condition     = output.public_dns == aws_eip.this.public_dns && output.instance_id == aws_instance.this.id
    error_message = "출력: public_dns, instance_id"
  }
}

# TASK-16#2 DLM: 매일 스냅샷, 7개 보관, 데이터 볼륨 태그 대상
run "dlm" {
  command = apply

  assert {
    condition     = one(aws_dlm_lifecycle_policy.data.policy_details).target_tags == tomap({ Snapshot = "board-test-pgdata" })
    error_message = "데이터 볼륨 태그 대상"
  }
  assert {
    condition     = toset(one(aws_dlm_lifecycle_policy.data.policy_details).resource_types) == toset(["VOLUME"])
    error_message = "볼륨 스냅샷"
  }
  assert {
    condition     = one(one(one(aws_dlm_lifecycle_policy.data.policy_details).schedule).create_rule).interval == 24
    error_message = "24시간마다"
  }
  assert {
    condition     = one(one(one(aws_dlm_lifecycle_policy.data.policy_details).schedule).retain_rule).count == 7
    error_message = "7개 보관"
  }
  assert {
    condition     = aws_dlm_lifecycle_policy.data.state == "ENABLED" && aws_dlm_lifecycle_policy.data.execution_role_arn == aws_iam_role.dlm.arn
    error_message = "DLM 정책 켜짐 + 전용 role"
  }
}

# TASK-16#2 SG: 8080 은 CloudFront origin-facing prefix list 에서만, 22 는 열지 않는다
run "security_group" {
  command = apply

  assert {
    condition     = data.aws_ec2_managed_prefix_list.cloudfront.name == "com.amazonaws.global.cloudfront.origin-facing"
    error_message = "CloudFront origin-facing managed prefix list"
  }
  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.app_from_cloudfront.prefix_list_id == "pl-3b927c52"
      && aws_vpc_security_group_ingress_rule.app_from_cloudfront.from_port == 8080
      && aws_vpc_security_group_ingress_rule.app_from_cloudfront.to_port == 8080
      && aws_vpc_security_group_ingress_rule.app_from_cloudfront.ip_protocol == "tcp"
      && aws_vpc_security_group_ingress_rule.app_from_cloudfront.cidr_ipv4 == null
    )
    error_message = "8080/tcp 는 prefix list 에서만(CIDR 개방 금지)"
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.app_from_cloudfront.security_group_id == aws_security_group.this.id && toset(aws_instance.this.vpc_security_group_ids) == toset([aws_security_group.this.id])
    error_message = "인스턴스는 이 SG 만"
  }
  assert {
    condition     = length(aws_security_group.this.ingress) == 0
    error_message = "SG 인라인 ingress(22 등) 없음 — 규칙은 별도 리소스 하나뿐"
  }
}

# TASK-16#2 IAM: SSM Core, ECR pull(해당 리포), CloudWatch Logs(이 로그 그룹, 14일), SSM 파라미터 경로 읽기 + KMS(SSM 경유), SMS 발송
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
    condition     = aws_cloudwatch_log_group.app.name == "/board/test" && aws_cloudwatch_log_group.app.retention_in_days == 14
    error_message = "로그 그룹은 여기서 만들고 14일 보관"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.app.policy).Statement :
      contains(s.Action, "ecr:BatchGetImage") && s.Resource == "arn:aws:ecr:us-east-1:123456789012:repository/board-api"
    ])
    error_message = "ECR pull 은 해당 리포만"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.app.policy).Statement :
      s.Action == ["ecr:GetAuthorizationToken"] && s.Resource == "*"
    ])
    error_message = "ECR 로그인 토큰(리소스 지정 불가)"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.app.policy).Statement :
      contains(s.Action, "logs:PutLogEvents") && contains(s.Resource, "arn:aws:logs:us-east-1:123456789012:log-group:/board/test:*")
    ])
    error_message = "이 로그 그룹에만 로그 쓰기"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.app.policy).Statement :
      contains(s.Action, "ssm:GetParametersByPath") && contains(s.Resource, "arn:aws:ssm:us-east-1:123456789012:parameter/board/test/*")
    ])
    error_message = "SSM 파라미터는 /board/test/* 만"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.app.policy).Statement :
      s.Action == ["kms:Decrypt"] && s.Condition.StringEquals["kms:ViaService"] == "ssm.us-east-1.amazonaws.com"
    ])
    error_message = "SecureString 복호화는 SSM 경유로만"
  }
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.app.policy).Statement : s.Action == ["sms-voice:SendTextMessage"]
    ])
    error_message = "End User Messaging SMS 발송(TASK-7)"
  }
  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.app.policy).Statement : !contains(flatten([s.Action]), "*")
    ])
    error_message = "와일드카드 Action 금지"
  }
  assert {
    condition     = length(aws_iam_role_policy.extra) == 0
    error_message = "extra_policy_jsons 기본은 없음"
  }
}

# TASK-16#2 extra_policy_jsons: uploads 모듈의 app_policy_json 같은 추가 정책을 role 에 붙인다
run "extra_policies" {
  command = apply

  variables {
    extra_policy_jsons = [jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = ["s3:GetObject"], Resource = "arn:aws:s3:::b/uploads/*" }] })]
  }

  assert {
    condition     = length(aws_iam_role_policy.extra) == 1 && aws_iam_role_policy.extra[0].role == aws_iam_role.this.id
    error_message = "추가 정책을 같은 role 에 inline 으로 붙인다"
  }
  assert {
    condition     = jsondecode(aws_iam_role_policy.extra[0].policy).Statement[0].Resource == "arn:aws:s3:::b/uploads/*"
    error_message = "정책 JSON 을 그대로"
  }
}
