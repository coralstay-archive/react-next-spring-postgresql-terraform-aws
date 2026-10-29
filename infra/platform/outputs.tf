output "region" {
  description = "리전"
  value       = var.region
}

output "vpc_id" {
  description = "VPC ID"
  value       = module.network.vpc_id
}

output "subnet_id" {
  description = "노드 public subnet"
  value       = module.network.subnet_id
}
