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
