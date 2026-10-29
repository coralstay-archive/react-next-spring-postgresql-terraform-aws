variable "region" {
  description = "기본 리전 (decision-11)"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "환경 이름 (태그)"
  type        = string
  default     = "prod"
}

variable "name" {
  description = "리소스 이름 접두어. 레거시 infra/main(board-prod)과 겹치지 않게"
  type        = string
  default     = "board-platform"
}

variable "availability_zone" {
  description = "노드·데이터 EBS·subnet AZ. 바꾸면 노드와 볼륨이 새로 만들어진다(데이터는 스냅샷에서 — data_snapshot_id)"
  type        = string
  default     = "us-east-1a"
}

variable "node_instance_type" {
  description = "k3s 노드 타입(arm64 스팟). decision-11: t4g.large"
  type        = string
  default     = "t4g.large"
}

variable "data_volume_size" {
  description = "데이터 볼륨 GB (k3s·PV — DB·Kafka·MinIO)"
  type        = number
  default     = 20
}

variable "data_snapshot_id" {
  description = "데이터 볼륨을 만들 스냅샷(snap-…). null 이면 빈 볼륨. 복원 리허설은 TASK-52"
  type        = string
  default     = null
}

variable "repo_commit" {
  description = "노드가 deploy/rocky/install.sh 를 받을 저장소 고정 커밋(40자리 SHA). 바꾸면 노드를 새로 만든다(데이터 볼륨·EIP 는 그대로)"
  type        = string
}

variable "k3s_install_cmd" {
  description = "install.sh 다음에 부를 k3s 설치 명령(TASK-36 스크립트). 비우면 설치하지 않는다"
  type        = string
  default     = ""
}

variable "domain_name" {
  description = "사용자 도메인. 비우면 <EIP>.sslip.io (도메인 결정은 TASK-52)"
  type        = string
  default     = ""
}

variable "enable_lb_node" {
  description = "HAProxy LB 노드(t4g.small + EIP 하나 더). 기본 false — Traefik 이 노드에서 바로 받는다(decision-11)"
  type        = bool
  default     = false
}

variable "enable_snapshots" {
  description = "데이터 볼륨 DLM 일일 스냅샷"
  type        = bool
  default     = true
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

variable "budget_monthly_usd" {
  description = "AWS Budgets 월 비용 한도(USD). decision-11 추정 $22~27"
  type        = number
  default     = 30
}

variable "budget_email" {
  description = "Budgets 알림 이메일. 비우면 Budgets 를 만들지 않는다"
  type        = string
  default     = ""
}

variable "cognito_domain_prefix" {
  description = "Cognito Hosted UI 도메인 prefix(리전 안에서 전역 고유, aws·amazon·cognito 불가)"
  type        = string
}

variable "cognito_deletion_protection" {
  description = "User Pool 삭제 보호"
  type        = bool
  default     = true
}

variable "cognito_extra_callback_urls" {
  description = "공개 주소 콜백 외에 추가로 허용할 Cognito 콜백(예: 로컬 개발)"
  type        = list(string)
  default     = []
}

variable "cognito_extra_logout_urls" {
  description = "공개 주소 로그아웃 URL 외에 추가로 허용할 URL"
  type        = list(string)
  default     = []
}
