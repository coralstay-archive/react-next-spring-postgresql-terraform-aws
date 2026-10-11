variable "name" {
  description = "리소스 이름 접두어 (예: board-prod)"
  type        = string
}

variable "github_repository" {
  description = "배포 워크플로가 있는 GitHub 리포 owner/repo"
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository 는 owner/repo 형식."
  }
}

variable "github_branch" {
  description = "배포를 허용할 브랜치"
  type        = string
  default     = "main"
}

variable "existing_oidc_provider_arn" {
  description = "계정에 이미 있는 token.actions.githubusercontent.com OIDC provider ARN. null 이면 새로 만든다"
  type        = string
  default     = null
}

variable "ecr_repository_arn" {
  description = "이미지를 push 할 ECR 리포 ARN"
  type        = string
}

variable "web_bucket_arn" {
  description = "프론트 정적 파일을 sync 할 버킷 ARN"
  type        = string
}

variable "distribution_arn" {
  description = "invalidation 할 CloudFront 배포 ARN"
  type        = string
}

variable "instance_arn" {
  description = "ssm:SendCommand 대상 EC2 인스턴스 ARN"
  type        = string
}

variable "tags" {
  description = "추가 태그"
  type        = map(string)
  default     = {}
}
