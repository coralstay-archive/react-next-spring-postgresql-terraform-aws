variable "domain_name" {
  description = "사용자 도메인 (예: board.example.com)"
  type        = string

  validation {
    condition     = can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}$", var.domain_name))
    error_message = "domain_name 은 소문자 FQDN (끝 점 없이)."
  }
}

variable "subject_alternative_names" {
  description = "같이 받을 추가 이름 (예: www.board.example.com)"
  type        = list(string)
  default     = []
}

variable "zone_name" {
  description = "Route53 hosted zone 이름. null 이면 domain_name"
  type        = string
  default     = null
}

variable "create_hosted_zone" {
  description = "true 면 zone 을 만든다(출력 name_servers 를 등록기관에 위임). false 면 기존 public zone 을 조회"
  type        = bool
  default     = false
}

variable "cloudfront_domain_name" {
  description = "alias 대상 CloudFront 도메인 (d…cloudfront.net)"
  type        = string
}

variable "cloudfront_hosted_zone_id" {
  description = "CloudFront hosted zone ID (frontend_cdn 출력, 보통 Z2FDTNDATAQYW2)"
  type        = string
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
