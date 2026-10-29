variable "name" {
  description = "리소스 이름 접두어 (예: board-platform)"
  type        = string
}

variable "vpc_id" {
  description = "SG 를 만들 VPC"
  type        = string
}

variable "subnet_id" {
  description = "노드를 둘 public subnet"
  type        = string
}

variable "availability_zone" {
  description = "subnet 의 AZ. 데이터 볼륨도 같은 AZ 에 만든다"
  type        = string
}

variable "instance_type" {
  description = "노드 타입(arm64). decision-11: t4g.large(8GB)"
  type        = string
  default     = "t4g.large"
}

variable "ami_owner" {
  description = "AMI 소유 계정(Rocky Enterprise Software Foundation)"
  type        = string
  default     = "792107900819"
}

variable "ami_name_pattern" {
  description = "AMI 이름 필터(Rocky 9.8 arm64)"
  type        = string
  default     = "Rocky-9-EC2-Base-9.8-*.aarch64"
}

variable "root_volume_size" {
  description = "루트 볼륨 GB (OS·이미지 캐시. k3s·DB 데이터는 데이터 볼륨)"
  type        = number
  default     = 30
}

variable "ssm_parameter_path" {
  description = "노드가 읽을 SSM 파라미터 경로(끝 / 없이). 이 경로와 그 아래만 읽는다"
  type        = string
  default     = "/board"

  validation {
    condition     = can(regex("^/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$", var.ssm_parameter_path))
    error_message = "ssm_parameter_path 는 /로 시작하고 /로 끝나지 않는 경로."
  }
}

variable "data_volume_size" {
  description = "데이터 볼륨 GB (k3s·PV — DB·Kafka·MinIO)"
  type        = number
  default     = 20
}

variable "data_snapshot_id" {
  description = "데이터 볼륨을 만들 스냅샷(snap-…). null 이면 빈 볼륨(첫 부팅에 포맷). 복원 리허설은 TASK-52"
  type        = string
  default     = null

  validation {
    condition     = var.data_snapshot_id == null || can(regex("^snap-[0-9a-f]{8,17}$", var.data_snapshot_id))
    error_message = "data_snapshot_id 는 null 또는 snap-<hex>."
  }
}

variable "data_device_name" {
  description = "데이터 볼륨 연결 장치 이름 (nitro 에서는 nvmeXn1 로 보이고 스크립트가 볼륨 ID 로 찾는다)"
  type        = string
  default     = "/dev/sdf"
}

variable "data_mount_path" {
  description = "데이터 볼륨 마운트 경로"
  type        = string
  default     = "/data"
}

variable "data_services" {
  description = "데이터 볼륨이 마운트돼야 뜨는 systemd 서비스(B2)"
  type        = list(string)
  default     = ["k3s.service"]
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
