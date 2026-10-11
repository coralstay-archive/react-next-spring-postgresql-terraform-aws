variable "name" {
  description = "리소스 이름 접두어 (예: board-prod)"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR"
  type        = string
  default     = "10.20.0.0/16"
}

variable "public_subnet_cidr" {
  description = "public subnet CIDR (VPC CIDR 안)"
  type        = string
  default     = "10.20.1.0/24"

  # subnet 네트워크 주소를 VPC prefix 길이로 잘랐을 때 VPC 네트워크 주소와 같아야 VPC 안이다
  validation {
    condition = (
      tonumber(split("/", var.public_subnet_cidr)[1]) >= tonumber(split("/", var.vpc_cidr)[1])
      && cidrhost("${cidrhost(var.public_subnet_cidr, 0)}/${split("/", var.vpc_cidr)[1]}", 0) == cidrhost(var.vpc_cidr, 0)
    )
    error_message = "public_subnet_cidr 는 VPC CIDR 안이어야 한다."
  }
}

variable "availability_zone" {
  description = "public subnet AZ. EC2 와 데이터 EBS 가 이 AZ 에 고정된다"
  type        = string
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
