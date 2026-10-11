variable "name" {
  description = "User Pool·App Client 이름 (예: board-prod)"
  type        = string
}

variable "public_base_url" {
  description = "브라우저가 보는 공개 주소(앱의 PUBLIC_BASE_URL). 콜백·로그아웃 URL 기본값을 여기서 만든다. https 또는 http://localhost[:port]"
  type        = string

  validation {
    condition     = can(regex("^(https://[^/\\s]+|http://localhost(:[0-9]+)?)/?$", var.public_base_url))
    error_message = "public_base_url 은 경로 없는 https://<host> 또는 http://localhost[:port] 여야 한다(Cognito 는 localhost 외 http 콜백을 받지 않는다)."
  }
}

variable "callback_urls" {
  description = "허용 콜백 URL. null 이면 [\"<public_base_url>/api/login/oauth2/code/cognito\"]"
  type        = list(string)
  default     = null
}

variable "logout_urls" {
  description = "허용 로그아웃 URL. null 이면 [\"<public_base_url>/\"]"
  type        = list(string)
  default     = null
}

variable "domain_prefix" {
  description = "Hosted UI 도메인 prefix → https://<prefix>.auth.<region>.amazoncognito.com. 리전 안에서 전역 고유"
  type        = string

  validation {
    condition = (
      can(regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$", var.domain_prefix))
      && !can(regex("aws|amazon|cognito", var.domain_prefix))
    )
    error_message = "domain_prefix 는 소문자·숫자·하이픈(1~63자)이고 aws, amazon, cognito 를 포함할 수 없다."
  }
}

variable "managed_login_version" {
  description = "1 = 클래식 Hosted UI, 2 = managed login(기본 branding 스타일을 같이 만든다)"
  type        = number
  default     = 1

  validation {
    condition     = contains([1, 2], var.managed_login_version)
    error_message = "managed_login_version 은 1 또는 2."
  }
}

variable "deletion_protection" {
  description = "User Pool 삭제 보호. 실습 환경을 terraform destroy 로 지우려면 false"
  type        = bool
  default     = true
}

variable "password_minimum_length" {
  description = "비밀번호 최소 길이"
  type        = number
  default     = 12

  validation {
    condition     = var.password_minimum_length >= 8 && var.password_minimum_length <= 99
    error_message = "password_minimum_length 는 8~99."
  }
}

variable "tags" {
  description = "User Pool 에 붙일 태그"
  type        = map(string)
  default     = {}
}
