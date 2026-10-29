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

output "node_instance_id" {
  description = "노드 instance ID (aws ssm start-session --target)"
  value       = module.k3s_node.instance_id
}

output "node_public_ip" {
  description = "노드 EIP (도메인 전에는 <ip>.sslip.io)"
  value       = module.k3s_node.public_ip
}
