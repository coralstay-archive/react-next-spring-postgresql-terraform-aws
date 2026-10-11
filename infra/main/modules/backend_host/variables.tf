variable "name" {
  description = "리소스 이름 접두어 (예: board-prod)"
  type        = string
}

variable "vpc_id" {
  description = "SG 를 만들 VPC"
  type        = string
}

variable "subnet_id" {
  description = "EC2 를 둘 public subnet"
  type        = string
}

variable "availability_zone" {
  description = "subnet 의 AZ. 데이터 EBS 도 같은 AZ 에 만든다"
  type        = string
}

variable "instance_type" {
  description = "EC2 타입(arm64). decision-1: t4g.small"
  type        = string
  default     = "t4g.small"
}

variable "ami_ssm_parameter" {
  description = "AMI ID 를 읽을 SSM 공개 파라미터 (AL2023 arm64)"
  type        = string
  default     = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

variable "root_volume_size" {
  description = "루트 볼륨 GB (도커 이미지·로그)"
  type        = number
  default     = 20
}

variable "data_volume_size" {
  description = "PostgreSQL 데이터 EBS GB"
  type        = number
  default     = 20
}

variable "data_device_name" {
  description = "데이터 EBS 연결 장치 이름 (nitro 에서는 /dev/nvmeXn1 로 보이고 user_data 가 볼륨 ID 로 찾는다)"
  type        = string
  default     = "/dev/sdf"
}

variable "data_mount_path" {
  description = "데이터 EBS 마운트 위치. deploy compose 가 postgres 의 /var/lib/postgresql 에 바인드한다"
  type        = string
  default     = "/srv/pgdata"
}

variable "deploy_dir" {
  description = "배포 파일(compose, env)을 둘 디렉터리"
  type        = string
  default     = "/opt/board"
}

variable "compose_version" {
  description = "docker compose plugin 버전 (GitHub 릴리스 태그, 예: v2.40.0). latest 면 최신"
  type        = string
  default     = "latest"
}

variable "app_port" {
  description = "API 포트 (CloudFront → EC2, HTTP)"
  type        = number
  default     = 8080
}

variable "ecr_repository_arn" {
  description = "앱 이미지 ECR 리포 ARN (pull 권한 범위)"
  type        = string
}

variable "log_group_name" {
  description = "CloudWatch Logs 그룹 (docker awslogs 기본 그룹)"
  type        = string
}

variable "log_retention_days" {
  description = "로그 보관 일수"
  type        = number
  default     = 14
}

variable "ssm_parameter_path" {
  description = "앱 시크릿 SSM 경로 (끝 / 없이, 예: /board/prod). 인스턴스가 이 경로 아래를 읽는다"
  type        = string

  validation {
    condition     = can(regex("^/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$", var.ssm_parameter_path))
    error_message = "ssm_parameter_path 는 /로 시작하고 /로 끝나지 않는 경로."
  }
}

variable "snapshot_time_utc" {
  description = "DLM 일일 스냅샷 시각(UTC, HH:MM). 기본 18:00 UTC = 03:00 KST"
  type        = string
  default     = "18:00"
}

variable "snapshot_retain_count" {
  description = "보관할 스냅샷 개수"
  type        = number
  default     = 7
}

variable "extra_policy_jsons" {
  description = "인스턴스 role 에 추가로 붙일 IAM 정책 JSON 목록 (예: uploads 모듈 app_policy_json)"
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
