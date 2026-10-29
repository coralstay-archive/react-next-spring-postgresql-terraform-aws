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

output "public_base_url" {
  description = "브라우저가 보는 공개 주소 → 앱 PUBLIC_BASE_URL, OAuth 콜백 기준"
  value       = local.public_base_url
}

output "lb_public_ip" {
  description = "lb_node EIP (enable_lb_node=false 면 null)"
  value       = length(module.lb_node) == 0 ? null : module.lb_node[0].public_ip
}

output "cognito" {
  description = "Cognito 앱 설정 → COGNITO_ISSUER_URI, COGNITO_CLIENT_ID, COGNITO_DOMAIN"
  value = {
    issuer_uri = module.cognito.issuer_uri
    client_id  = module.cognito.client_id
    domain_url = module.cognito.domain_url
  }
}

output "cognito_client_secret" {
  description = "COGNITO_CLIENT_SECRET (SSM SecureString 으로 옮긴다)"
  value       = module.cognito.client_secret
  sensitive   = true
}

# B3 이 스택이 쓰는 관리형 서비스(예외 목록 안). 리소스 종류 전체 검사는 scripts/test/platform_test.sh
output "managed_services" {
  description = "쓰는 관리형 서비스(decision-11 예외: Cognito·SSM·EBS 스냅샷·Budgets)"
  value = toset(compact([
    "cognito",
    "ssm", # Session Manager·Parameter Store(노드 role)
    var.enable_snapshots ? "ebs-snapshots" : "",
    var.budget_email != "" ? "budgets" : "",
  ]))
}
