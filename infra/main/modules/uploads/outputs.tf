output "bucket_name" {
  description = "버킷 이름 → 앱 env UPLOADS_BUCKET"
  value       = aws_s3_bucket.this.id
}

output "bucket_arn" {
  description = "버킷 ARN"
  value       = aws_s3_bucket.this.arn
}

output "bucket_regional_domain_name" {
  description = "CloudFront S3 오리진 도메인(OAC). /uploads/* 동작이 이 오리진을 쓴다(TASK-16)"
  value       = aws_s3_bucket.this.bucket_regional_domain_name
}

output "uploads_prefix" {
  description = "앱이 쓰는 키 접두어. CloudFront 경로 패턴 /uploads/* 와 같다"
  value       = local.uploads_prefix
}

output "app_policy_json" {
  description = "앱(EC2 role)에 붙일 IAM 정책 JSON: uploads/* 의 Put/Get/Delete Object(+Tagging) + uploads/ 접두어 ListBucket"
  value       = jsonencode(local.app_policy)
}

output "bucket_policy_json" {
  description = "적용된 버킷 정책 JSON (CloudFront OAC 배포 ARN 확인용)"
  value       = aws_s3_bucket_policy.this.policy
}

output "cors_allowed_origins" {
  description = "presigned PUT CORS 허용 origin"
  value       = var.allowed_origins
}
