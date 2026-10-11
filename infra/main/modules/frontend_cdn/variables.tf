variable "name" {
  description = "리소스 이름 접두어 (예: board-prod)"
  type        = string
}

variable "web_bucket_name" {
  description = "정적 프론트(Next export) 버킷 이름(전역 고유)"
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.web_bucket_name))
    error_message = "web_bucket_name 은 소문자·숫자·점·하이픈 3~63자."
  }
}

variable "web_force_destroy" {
  description = "객체가 있어도 web 버킷을 destroy 할지(빌드 산출물이라 다시 배포하면 된다)"
  type        = bool
  default     = true
}

variable "ecr_repository_name" {
  description = "백엔드 이미지 ECR 리포 이름"
  type        = string
}

variable "ecr_keep_images" {
  description = "ECR 에 남길 최근 이미지 수"
  type        = number
  default     = 20
}

variable "ecr_force_delete" {
  description = "이미지가 있어도 ECR 리포를 destroy 할지"
  type        = bool
  default     = false
}

variable "api_origin_domain" {
  description = "/api/* custom origin 도메인 (backend_host 의 EIP public DNS)"
  type        = string
}

variable "api_origin_port" {
  description = "/api/* origin HTTP 포트"
  type        = number
  default     = 8080
}

variable "uploads_bucket_regional_domain_name" {
  description = "/uploads/* origin (uploads 모듈 bucket_regional_domain_name). 버킷 정책(배포 ARN 허용)은 uploads 모듈이 만든다"
  type        = string
}

variable "price_class" {
  description = "CloudFront price class"
  type        = string
  default     = "PriceClass_100"
}

variable "aliases" {
  description = "사용자 도메인(CNAME). 비우면 *.cloudfront.net 만"
  type        = list(string)
  default     = []

  validation {
    condition     = length(var.aliases) == 0 || var.acm_certificate_arn != null
    error_message = "aliases 를 쓰려면 acm_certificate_arn(us-east-1)이 필요하다."
  }
}

variable "acm_certificate_arn" {
  description = "aliases 용 ACM 인증서 ARN(us-east-1, dns_cert 모듈 출력). null 이면 CloudFront 기본 인증서"
  type        = string
  default     = null
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
