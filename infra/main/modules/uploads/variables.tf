variable "bucket_name" {
  description = "첨부 버킷 이름(전역 고유). 앱 env UPLOADS_BUCKET"
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.bucket_name))
    error_message = "bucket_name 은 소문자·숫자·점·하이픈 3~63자."
  }
}

variable "allowed_origins" {
  description = "presigned PUT(브라우저 직접 업로드)을 허용할 origin. 보통 [PUBLIC_BASE_URL]. https://<host> 또는 http://localhost[:port]"
  type        = list(string)

  validation {
    condition = length(var.allowed_origins) > 0 && alltrue([
      for origin in var.allowed_origins : can(regex("^(https://[^/\\s*]+|http://localhost(:[0-9]+)?)$", origin))
    ])
    error_message = "allowed_origins 는 하나 이상이고, 각 값은 경로 없는 https://<host> 또는 http://localhost[:port] 여야 한다."
  }
}

variable "cloudfront_distribution_arn" {
  description = "uploads/* 를 OAC 로 읽을 CloudFront 배포 ARN(TASK-16 루트 스택이 넘긴다). null 이면 CloudFront 읽기 허용 문장을 넣지 않는다"
  type        = string
  default     = null
}

variable "pending_expiration_days" {
  description = "status=pending 태그가 남은(confirm 되지 않은) uploads/ 객체를 지울 때까지의 일수"
  type        = number
  default     = 1

  validation {
    condition     = var.pending_expiration_days >= 1
    error_message = "pending_expiration_days 는 1 이상."
  }
}

variable "force_destroy" {
  description = "객체가 있어도 terraform destroy 로 버킷을 지울지. 실습 환경 정리용"
  type        = bool
  default     = false
}

variable "tags" {
  description = "버킷에 붙일 태그"
  type        = map(string)
  default     = {}
}
