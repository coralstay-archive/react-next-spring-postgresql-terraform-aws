variable "region" {
  description = "기본 리전 (decision-1)"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "환경 이름 (태그)"
  type        = string
  default     = "prod"
}

variable "name" {
  description = "리소스 이름 접두어"
  type        = string
  default     = "board-prod"
}

variable "availability_zone" {
  description = "EC2·데이터 EBS·subnet AZ. 바꾸면 인스턴스와 볼륨이 새로 만들어진다(데이터는 스냅샷에서 복원)"
  type        = string
  default     = "us-east-1a"
}

variable "instance_type" {
  description = "백엔드 EC2 타입(arm64)"
  type        = string
  default     = "t4g.small"
}

variable "data_volume_size" {
  description = "PostgreSQL 데이터 EBS GB"
  type        = number
  default     = 20
}

variable "web_bucket_name" {
  description = "정적 프론트 버킷 이름(전역 고유)"
  type        = string
}

variable "uploads_bucket_name" {
  description = "이미지 첨부 버킷 이름(전역 고유) → 앱 UPLOADS_BUCKET"
  type        = string
}

variable "uploads_force_destroy" {
  description = "terraform destroy 때 첨부 객체가 있어도 버킷을 지울지"
  type        = bool
  default     = false
}

variable "ecr_repository_name" {
  description = "백엔드 이미지 ECR 리포 이름"
  type        = string
  default     = "board-api"
}

variable "ssm_parameter_path" {
  description = "앱 시크릿 SSM 경로(TASK-17 put-secrets.sh 가 채운다). 인스턴스 role 이 이 경로 아래만 읽는다"
  type        = string
  default     = "/board/prod"
}

variable "log_group_name" {
  description = "앱 컨테이너 로그 그룹(docker awslogs 기본)"
  type        = string
  default     = "/board/prod"
}

variable "cognito_domain_prefix" {
  description = "Cognito Hosted UI 도메인 prefix(리전 안에서 전역 고유, aws·amazon·cognito 불가)"
  type        = string
}

variable "cognito_deletion_protection" {
  description = "User Pool 삭제 보호. 실습 환경을 destroy 하려면 false 로 apply 한 뒤 destroy"
  type        = bool
  default     = true
}

variable "cognito_extra_callback_urls" {
  description = "운영 콜백 외에 추가로 허용할 Cognito 콜백(예: http://localhost:3000/api/login/oauth2/code/cognito)"
  type        = list(string)
  default     = []
}

variable "cognito_extra_logout_urls" {
  description = "운영 로그아웃 URL 외에 추가로 허용할 URL(예: http://localhost:3000/)"
  type        = list(string)
  default     = []
}

variable "domain_name" {
  description = "사용자 도메인. 비우면 *.cloudfront.net 만 쓴다(decision-9). 주면 dns_cert(Route53 + us-east-1 ACM)를 켠다"
  type        = string
  default     = ""
}

variable "subject_alternative_names" {
  description = "도메인 인증서의 추가 이름(예: www.<domain>)"
  type        = list(string)
  default     = []
}

variable "hosted_zone_name" {
  description = "Route53 zone 이름. null 이면 domain_name"
  type        = string
  default     = null
}

variable "create_hosted_zone" {
  description = "true 면 Route53 zone 을 만든다(출력 name_servers 를 등록기관에 위임)"
  type        = bool
  default     = false
}

variable "github_repository" {
  description = "배포 워크플로 리포 owner/repo. 비우면 ci_oidc(GitHub OIDC + 배포 role)를 만들지 않는다"
  type        = string
  default     = ""
}

variable "github_branch" {
  description = "배포를 허용할 브랜치"
  type        = string
  default     = "main"
}

variable "existing_github_oidc_provider_arn" {
  description = "계정에 이미 GitHub OIDC provider 가 있으면 그 ARN(없으면 null → 새로 만든다)"
  type        = string
  default     = null
}
