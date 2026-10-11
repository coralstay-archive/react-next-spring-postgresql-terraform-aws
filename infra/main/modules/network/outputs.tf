output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.this.id
}

output "subnet_id" {
  description = "public subnet ID (EC2)"
  value       = aws_subnet.public.id
}

output "availability_zone" {
  description = "subnet AZ (EC2·데이터 EBS 가 같은 AZ)"
  value       = aws_subnet.public.availability_zone
}
