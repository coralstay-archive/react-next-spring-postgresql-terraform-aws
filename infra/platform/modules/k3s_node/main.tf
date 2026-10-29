# k3s 노드 (TASK-65, decision-11): Rocky 9.8 arm64 t4g.large 스팟 1대. DB·Kafka·MinIO·모니터링이 모두 이 노드의 k3s 안에서 돈다.
# SSH 없이 SSM 으로만 접속하고(B1), Traefik 이 80·443 을 바로 받는다(LB·CloudFront 없음).

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

# Rocky Enterprise Software Foundation 공식 계정의 Rocky 9.8 arm64 이미지 중 최신
data "aws_ami" "rocky" {
  most_recent = true
  owners      = [var.ami_owner]

  filter {
    name   = "name"
    values = [var.ami_name_pattern]
  }

  filter {
    name   = "architecture"
    values = ["arm64"]
  }
}

locals {
  region       = data.aws_region.current.region
  snapshot_tag = { Snapshot = "${var.name}-data" }
  param_arn    = "arn:aws:ssm:${local.region}:${data.aws_caller_identity.current.account_id}:parameter${var.ssm_parameter_path}"
}

# --- IAM -------------------------------------------------------------------

resource "aws_iam_role" "this" {
  name = "${var.name}-node"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = var.tags
}

# Session Manager 접속·Run Command (22 대신)
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "params" {
  name = "params"
  role = aws_iam_role.this.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadParameters"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
        Resource = [local.param_arn, "${local.param_arn}/*"]
      },
      {
        # SecureString 기본 키(aws/ssm)는 처음 쓸 때 생기므로 ARN 대신 SSM 경유 조건으로 제한한다
        Sid       = "DecryptViaSsm"
        Effect    = "Allow"
        Action    = ["kms:Decrypt"]
        Resource  = "*"
        Condition = { StringEquals = { "kms:ViaService" = "ssm.${local.region}.amazonaws.com" } }
      },
    ]
  })
}

resource "aws_iam_instance_profile" "this" {
  name = "${var.name}-node"
  role = aws_iam_role.this.name
  tags = var.tags
}

# --- 보안 그룹 ---------------------------------------------------------------

resource "aws_security_group" "this" {
  name        = "${var.name}-node"
  description = "k3s node: 80/443 only (Traefik), no SSH - SSM"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-node" })
}

# B1 80·443 만 IPv4·IPv6 로 연다. 22 는 열지 않는다(SSM Session Manager)
resource "aws_vpc_security_group_ingress_rule" "web" {
  for_each = {
    for p in setproduct([80, 443], ["0.0.0.0/0", "::/0"]) : "${p[0]}-${p[1]}" => { port = p[0], cidr = p[1] }
  }

  security_group_id = aws_security_group.this.id
  description       = "Traefik ${each.value.port}"
  ip_protocol       = "tcp"
  from_port         = each.value.port
  to_port           = each.value.port
  cidr_ipv4         = strcontains(each.value.cidr, ":") ? null : each.value.cidr
  cidr_ipv6         = strcontains(each.value.cidr, ":") ? each.value.cidr : null
}

resource "aws_vpc_security_group_egress_rule" "all_ipv4" {
  security_group_id = aws_security_group.this.id
  description       = "dnf, GHCR, SSM, Let's Encrypt, Cognito, SMS"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_vpc_security_group_egress_rule" "all_ipv6" {
  security_group_id = aws_security_group.this.id
  description       = "dnf, GHCR, SSM, Let's Encrypt, Cognito, SMS"
  cidr_ipv6         = "::/0"
  ip_protocol       = "-1"
}

# --- 노드 ------------------------------------------------------------------

resource "aws_instance" "this" {
  ami                    = data.aws_ami.rocky.id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  availability_zone      = var.availability_zone
  vpc_security_group_ids = [aws_security_group.this.id]
  iam_instance_profile   = aws_iam_instance_profile.this.name

  # 스팟(decision-11): persistent 요청 + 회수 시 stop — 루트·데이터 볼륨과 EIP 가 남고 용량이 돌아오면 다시 켜진다
  instance_market_options {
    market_type = "spot"
    spot_options {
      spot_instance_type             = "persistent"
      instance_interruption_behavior = "stop"
    }
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2 # 파드(컨테이너 네트워크)의 SDK 도 IMDSv2 로 인스턴스 프로파일을 받는다
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size
    encrypted             = true
    delete_on_termination = true
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    volume_id       = aws_ebs_volume.data.id
    data_mount_path = var.data_mount_path
    data_services   = join(" ", var.data_services)
    data_volume_b64 = filebase64("${path.module}/files/data-volume.sh")
  })

  tags = merge(var.tags, { Name = "${var.name}-node" })

  lifecycle {
    # 새 AMI 가 나올 때마다 노드를 바꾸지 않는다. 바꾸려면 -replace 로 명시한다(데이터는 별도 볼륨)
    ignore_changes = [ami]
  }
}

# --- 데이터 볼륨 (TASK-65#3) ---------------------------------------------------
# 노드와 따로 사는 gp3 볼륨. 노드를 바꿔도(user_data 변경·AMI 교체) 데이터는 남는다. data_snapshot_id 로 스냅샷에서 만들 수 있다.

resource "aws_ebs_volume" "data" {
  availability_zone = var.availability_zone
  size              = var.data_volume_size
  type              = "gp3"
  encrypted         = true
  snapshot_id       = var.data_snapshot_id
  final_snapshot    = true # 볼륨을 지우면 마지막 스냅샷을 남긴다

  tags = merge(var.tags, local.snapshot_tag, { Name = "${var.name}-data" })
}

resource "aws_volume_attachment" "data" {
  device_name                    = var.data_device_name
  volume_id                      = aws_ebs_volume.data.id
  instance_id                    = aws_instance.this.id
  stop_instance_before_detaching = true
}

# 공인 IPv4 1개(decision-11). 스팟이 stop/start 돼도 주소가 그대로다
resource "aws_eip" "this" {
  domain   = "vpc"
  instance = aws_instance.this.id
  tags     = merge(var.tags, { Name = "${var.name}-node" })
}
