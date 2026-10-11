output "certificate_arn" {
  description = "검증이 끝난 ACM 인증서 ARN(us-east-1) → frontend_cdn acm_certificate_arn"
  value       = aws_acm_certificate_validation.this.certificate_arn
}

output "aliases" {
  description = "CloudFront aliases"
  value       = local.names
}

output "zone_id" {
  description = "Route53 hosted zone ID"
  value       = local.zone_id
}

output "name_servers" {
  description = "zone 을 만든 경우 등록기관에 위임할 NS"
  value       = var.create_hosted_zone ? aws_route53_zone.this[0].name_servers : []
}
