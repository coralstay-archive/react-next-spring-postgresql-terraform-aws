# 백엔드 호스트 (TASK-16#2, decision-1·2): EC2 1대에서 Docker Compose 로 api + postgres.
# 데이터는 별도 EBS(같은 AZ)에 두고 DLM 으로 매일 스냅샷. SSH 없이 SSM 으로만 접근하고,
# 8080 은 CloudFront origin-facing prefix list 에서만 받는다(decision-3).

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

data "aws_ssm_parameter" "ami" {
  name = var.ami_ssm_parameter
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

locals {
  region       = data.aws_region.current.region
  account_id   = data.aws_caller_identity.current.account_id
  snapshot_tag = { Snapshot = "${var.name}-pgdata" }
  param_arn    = "arn:aws:ssm:${local.region}:${local.account_id}:parameter${var.ssm_parameter_path}"

  app_policy = {
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid      = "EcrPull"
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
        Resource = var.ecr_repository_arn
      },
      {
        Sid      = "Logs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
        Resource = [aws_cloudwatch_log_group.app.arn, "${aws_cloudwatch_log_group.app.arn}:*"]
      },
      {
        Sid      = "ReadAppParameters"
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
      {
        # 휴대폰 OTP (TASK-7, End User Messaging SMS). 발신 identity 는 운영 중 정해지므로 리소스를 좁히지 않는다
        Sid      = "SendOtpSms"
        Effect   = "Allow"
        Action   = ["sms-voice:SendTextMessage"]
        Resource = "*"
      },
    ]
  }
}

# --- IAM -------------------------------------------------------------------

resource "aws_iam_role" "this" {
  name = "${var.name}-backend"
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

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "app" {
  name   = "app"
  role   = aws_iam_role.this.id
  policy = jsonencode(local.app_policy)
}

resource "aws_iam_role_policy" "extra" {
  count = length(var.extra_policy_jsons)

  name   = "extra-${count.index}"
  role   = aws_iam_role.this.id
  policy = var.extra_policy_jsons[count.index]
}

resource "aws_iam_instance_profile" "this" {
  name = "${var.name}-backend"
  role = aws_iam_role.this.name
  tags = var.tags
}

# --- 로그 ------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "app" {
  name              = var.log_group_name
  retention_in_days = var.log_retention_days
  tags              = var.tags
}

# --- 보안 그룹 ---------------------------------------------------------------

resource "aws_security_group" "this" {
  name        = "${var.name}-backend"
  description = "backend host: ${var.app_port} from CloudFront origin-facing only, no SSH"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-backend" })
}

# CloudFront → EC2 구간은 HTTP 라 이 prefix list 로만 연다(decision-3). 22 는 열지 않는다(SSM 사용).
resource "aws_vpc_security_group_ingress_rule" "app_from_cloudfront" {
  security_group_id = aws_security_group.this.id
  description       = "API from CloudFront origin-facing"
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = var.app_port
  to_port           = var.app_port
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.this.id
  description       = "ECR, SSM, CloudWatch, S3, OAuth providers, SMS"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# --- EC2 -------------------------------------------------------------------

resource "aws_ebs_volume" "data" {
  availability_zone = var.availability_zone
  size              = var.data_volume_size
  type              = "gp3"
  encrypted         = true
  final_snapshot    = true # 볼륨을 지우면 마지막 스냅샷을 남긴다

  tags = merge(var.tags, local.snapshot_tag, { Name = "${var.name}-pgdata" })
}

resource "aws_instance" "this" {
  ami                    = nonsensitive(data.aws_ssm_parameter.ami.value)
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  availability_zone      = var.availability_zone
  vpc_security_group_ids = [aws_security_group.this.id]
  iam_instance_profile   = aws_iam_instance_profile.this.name

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2 # 브리지 네트워크 컨테이너(앱의 AWS SDK)도 IMDSv2 로 인스턴스 프로파일을 받는다
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size
    encrypted             = true
    delete_on_termination = true
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    volume_id        = aws_ebs_volume.data.id
    volume_id_nodash = replace(aws_ebs_volume.data.id, "-", "")
    data_device_name = var.data_device_name
    data_mount_path  = var.data_mount_path
    deploy_dir       = var.deploy_dir
    compose_version  = var.compose_version
    region           = local.region
    log_group_name   = var.log_group_name
  })

  tags = merge(var.tags, { Name = "${var.name}-backend" })

  lifecycle {
    # 새 AMI 가 나올 때마다 인스턴스를 바꾸지 않는다. 바꾸려면 -replace 로 명시한다(데이터는 별도 EBS)
    ignore_changes = [ami]
  }
}

resource "aws_volume_attachment" "data" {
  device_name                    = var.data_device_name
  volume_id                      = aws_ebs_volume.data.id
  instance_id                    = aws_instance.this.id
  stop_instance_before_detaching = true
}

resource "aws_eip" "this" {
  domain   = "vpc"
  instance = aws_instance.this.id
  tags     = merge(var.tags, { Name = "${var.name}-backend" })
}

# --- DLM 일일 스냅샷 ----------------------------------------------------------

resource "aws_iam_role" "dlm" {
  name = "${var.name}-dlm"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "dlm.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "dlm" {
  role       = aws_iam_role.dlm.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
}

resource "aws_dlm_lifecycle_policy" "data" {
  description        = "${var.name} pgdata daily snapshots"
  execution_role_arn = aws_iam_role.dlm.arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]
    target_tags    = local.snapshot_tag

    schedule {
      name      = "daily"
      copy_tags = true

      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = [var.snapshot_time_utc]
      }

      retain_rule {
        count = var.snapshot_retain_count
      }
    }
  }

  tags = var.tags
}
