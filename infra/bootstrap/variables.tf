variable "region" {
  description = "state 버킷 리전(decision-1: us-east-1)"
  type        = string
  default     = "us-east-1"
}

variable "state_bucket_name" {
  description = "Terraform state 버킷 이름(전역 고유). 예: board-tfstate-<account id>"
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.state_bucket_name))
    error_message = "state_bucket_name 은 소문자·숫자·점·하이픈 3~63자."
  }
}

variable "state_key" {
  description = "main 스택 state 객체 키(backend_config 출력에 쓴다)"
  type        = string
  default     = "main/prod/terraform.tfstate"
}

variable "noncurrent_version_days" {
  description = "이전 state 버전을 보관할 일수"
  type        = number
  default     = 90
}
