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

variable "repo_url" {
  description = "노드가 deploy/rocky/install.sh 를 받을 저장소(공개, https)"
  type        = string
  default     = "https://github.com/coralstay/react-next-spring-postgresql-terraform-aws.git"
}

variable "repo_commit" {
  description = "install.sh 를 받을 고정 커밋(40자리 SHA). 바꾸면 노드를 새로 만든다"
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.repo_commit))
    error_message = "repo_commit 은 40자리 커밋 SHA(브랜치·태그 이름 불가)."
  }
}

variable "node_user" {
  description = "Podman 사용자 소켓·linger 를 켤 사용자(Rocky AMI 기본 사용자)"
  type        = string
  default     = "rocky"
}

variable "k3s_install_cmd" {
  description = "install.sh 다음에 부를 k3s 설치 명령(TASK-36 스크립트). 비우면 설치하지 않는다"
  type        = string
  default     = ""
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
