output "distribution_id" {
  description = "CloudFront 배포 ID (invalidation)"
  value       = aws_cloudfront_distribution.this.id
}

output "distribution_arn" {
  description = "CloudFront 배포 ARN (S3 OAC 정책·CI invalidation 권한)"
  value       = aws_cloudfront_distribution.this.arn
}

output "domain_name" {
  description = "*.cloudfront.net 도메인"
  value       = aws_cloudfront_distribution.this.domain_name
}

output "hosted_zone_id" {
  description = "CloudFront hosted zone ID (Route53 alias 레코드)"
  value       = aws_cloudfront_distribution.this.hosted_zone_id
}

output "web_bucket" {
  description = "정적 프론트 버킷 (aws s3 sync 대상)"
  value       = aws_s3_bucket.web.id
}

output "web_bucket_arn" {
  description = "정적 프론트 버킷 ARN"
  value       = aws_s3_bucket.web.arn
}

output "ecr_repository_url" {
  description = "백엔드 이미지 ECR URL"
  value       = aws_ecr_repository.api.repository_url
}

output "ecr_repository_arn" {
  description = "ECR 리포 ARN"
  value       = aws_ecr_repository.api.arn
}
