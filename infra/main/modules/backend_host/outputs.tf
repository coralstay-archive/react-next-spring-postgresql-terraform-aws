output "instance_id" {
  description = "EC2 instance ID (SSM send-command 대상)"
  value       = aws_instance.this.id
}

output "instance_arn" {
  description = "EC2 instance ARN (CI role 의 ssm:SendCommand 범위)"
  value       = aws_instance.this.arn
}

output "public_dns" {
  description = "EIP public DNS → CloudFront /api/* custom origin"
  value       = aws_eip.this.public_dns
}

output "public_ip" {
  description = "EIP"
  value       = aws_eip.this.public_ip
}

output "role_name" {
  description = "인스턴스 role 이름"
  value       = aws_iam_role.this.name
}

output "security_group_id" {
  description = "backend SG"
  value       = aws_security_group.this.id
}

output "data_volume_id" {
  description = "PostgreSQL 데이터 EBS"
  value       = aws_ebs_volume.data.id
}

output "data_mount_path" {
  description = "데이터 EBS 마운트 경로(deploy compose 의 postgres 바인드 원본)"
  value       = var.data_mount_path
}

output "log_group_name" {
  description = "CloudWatch Logs 그룹"
  value       = aws_cloudwatch_log_group.app.name
}

output "deploy_dir" {
  description = "배포 디렉터리"
  value       = var.deploy_dir
}
