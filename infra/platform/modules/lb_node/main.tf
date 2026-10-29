# HAProxy LB 노드 (TASK-65#5, decision-11): 기본으로 꺼 둔다(루트 enable_lb_node). 켜면 80·443 을 TCP 그대로 k3s 노드의 Traefik 에 넘긴다 —
# TLS 종료는 노드(cert-manager)에 남는다. 관리형 ALB/NLB 대신 쓰는 자리라 온디맨드 t4g.small 한 대, SSM 접속(22 없음).

resource "aws_iam_role" "this" {
  name = "${var.name}-lb"
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

# SSM 에이전트 최소 권한(Session Manager = ssmmessages, Run Command = ec2messages). AmazonSSMManagedInstanceCore 는 쓰지 않는다 —
# ssm:GetParameter* 가 모든 파라미터에 열리기 때문이다. 리소스 수준 권한이 없는 동작이라 Resource 는 *
resource "aws_iam_role_policy" "ssm_agent" {
  name = "ssm-agent"
  role = aws_iam_role.this.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "SsmAgent"
      Effect = "Allow"
      Action = [
        "ssm:UpdateInstanceInformation",
        "ssmmessages:CreateControlChannel", "ssmmessages:CreateDataChannel", "ssmmessages:OpenControlChannel", "ssmmessages:OpenDataChannel",
        "ec2messages:AcknowledgeMessage", "ec2messages:DeleteMessage", "ec2messages:FailMessage", "ec2messages:GetEndpoint", "ec2messages:GetMessages", "ec2messages:SendReply",
      ]
      Resource = "*"
    }]
  })
}

resource "aws_iam_instance_profile" "this" {
  name = "${var.name}-lb"
  role = aws_iam_role.this.name
  tags = var.tags
}

resource "aws_security_group" "this" {
  name        = "${var.name}-lb"
  description = "HAProxy: 80/443 only, no SSH - SSM"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-lb" })
}

resource "aws_vpc_security_group_ingress_rule" "web" {
  for_each = {
    for p in setproduct([80, 443], ["0.0.0.0/0", "::/0"]) : "${p[0]}-${p[1]}" => { port = p[0], cidr = p[1] }
  }

  security_group_id = aws_security_group.this.id
  description       = "HAProxy ${each.value.port}"
  ip_protocol       = "tcp"
  from_port         = each.value.port
  to_port           = each.value.port
  cidr_ipv4         = strcontains(each.value.cidr, ":") ? null : each.value.cidr
  cidr_ipv6         = strcontains(each.value.cidr, ":") ? each.value.cidr : null
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.this.id
  description       = "k3s node, dnf, SSM"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_instance" "this" {
  ami                    = var.ami_id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.this.id]
  iam_instance_profile   = aws_iam_instance_profile.this.name

  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size
    encrypted             = true
    delete_on_termination = true
  }

  user_data                   = templatefile("${path.module}/user_data.sh.tftpl", { backend_ip = var.backend_ip })
  user_data_replace_on_change = true # OPS-2

  tags = merge(var.tags, { Name = "${var.name}-lb" })

  lifecycle {
    ignore_changes = [ami]
  }
}

resource "aws_eip" "this" {
  domain   = "vpc"
  instance = aws_instance.this.id
  tags     = merge(var.tags, { Name = "${var.name}-lb" })
}
