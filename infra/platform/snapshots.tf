# 데이터 볼륨 일일 스냅샷 (TASK-65#5, enable_snapshots). decision-11 이 남긴 몇 안 되는 관리형 기능 — EBS 스냅샷(DLM)
locals {
  snapshots = var.enable_snapshots ? 1 : 0
}

resource "aws_iam_role" "dlm" {
  count = local.snapshots

  name = "${var.name}-dlm"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "dlm.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "dlm" {
  count = local.snapshots

  role       = aws_iam_role.dlm[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
}

resource "aws_dlm_lifecycle_policy" "data" {
  count = local.snapshots

  description        = "${var.name} data volume daily snapshots"
  execution_role_arn = aws_iam_role.dlm[0].arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]
    target_tags    = module.k3s_node.snapshot_tag

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
}
