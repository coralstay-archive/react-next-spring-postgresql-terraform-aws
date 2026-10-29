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

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
