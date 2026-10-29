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
