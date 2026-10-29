variable "name" {
  description = "리소스 이름 접두어"
  type        = string
}

variable "vpc_id" {
  description = "SG 를 만들 VPC"
  type        = string
}

variable "subnet_id" {
  description = "LB 를 둘 public subnet"
  type        = string
}

variable "ami_id" {
  description = "AMI (k3s_node 와 같은 Rocky 9.8 arm64)"
  type        = string
}

variable "instance_type" {
  description = "LB 타입(arm64)"
  type        = string
  default     = "t4g.small"
}

variable "root_volume_size" {
  description = "루트 볼륨 GB"
  type        = number
  default     = 10
}

variable "backend_ip" {
  description = "넘길 곳(k3s 노드 사설 IP)"
  type        = string
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
