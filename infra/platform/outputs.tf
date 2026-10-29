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

output "node_ready_check" {
  description = "배포 전에 노드에서 반드시 돌릴 준비 확인(SSM 세션에서, OPS-10)"
  value       = module.k3s_node.ready_check_command
}

output "repo_commit" {
  description = "노드가 install.sh 를 받은 커밋"
  value       = var.repo_commit
}

output "data_volume_id" {
  description = "데이터 볼륨 ID"
  value       = module.k3s_node.data_volume_id
}
