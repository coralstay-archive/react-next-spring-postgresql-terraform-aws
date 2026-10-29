# AWS 실배포 플랫폼 스택 (TASK-65, decision-11). 관리형 미사용 — k3s 노드 1대(t4g.large 스팟)가 DB·Kafka·MinIO·모니터링을 모두 돌린다.
# 레거시 infra/main 은 TASK-52 AC #5(철거)까지 그대로 두고, 여기는 apply 하지 않고 tftest(mock_provider)로만 검증한다.

locals {
  # 도메인 전에는 EIP 의 sslip.io 이름(3.1.2.3 → 3-1-2-3.sslip.io)으로 Let's Encrypt 인증서를 받는다(decision-11, 도메인은 TASK-52)
  public_host     = var.domain_name != "" ? var.domain_name : "${replace(module.k3s_node.public_ip, ".", "-")}.sslip.io"
  public_base_url = "https://${local.public_host}"
}

# VPC 1개 + public subnet 1개 + IGW. NAT·private subnet 은 두지 않는다(레거시 모듈 재사용).
module "network" {
  source = "../main/modules/network"

  name              = var.name
  availability_zone = var.availability_zone
}

module "k3s_node" {
  source = "./modules/k3s_node"

  name              = var.name
  vpc_id            = module.network.vpc_id
  subnet_id         = module.network.subnet_id
  availability_zone = module.network.availability_zone
  instance_type     = var.node_instance_type
  data_volume_size  = var.data_volume_size
  data_snapshot_id  = var.data_snapshot_id
  repo_commit       = var.repo_commit
  k3s_install_cmd   = var.k3s_install_cmd
}

# HAProxy LB 노드(기본 꺼짐 — Traefik 이 노드에서 바로 받는다). 관리형 ALB/NLB 를 쓰지 않는다(B3)
module "lb_node" {
  source = "./modules/lb_node"
  count  = var.enable_lb_node ? 1 : 0

  name       = var.name
  vpc_id     = module.network.vpc_id
  subnet_id  = module.network.subnet_id
  ami_id     = module.k3s_node.ami_id
  backend_ip = module.k3s_node.private_ip
}

# 로그인 수단(decision-11 유지 항목). 레거시 모듈을 그대로 쓴다
module "cognito" {
  source = "../main/modules/cognito"

  name                = var.name
  public_base_url     = local.public_base_url
  domain_prefix       = var.cognito_domain_prefix
  deletion_protection = var.cognito_deletion_protection

  # 추가 URL 이 없으면 null → 모듈 기본값(<public_base_url>/api/login/oauth2/code/cognito, <public_base_url>/)
  callback_urls = length(var.cognito_extra_callback_urls) == 0 ? null : concat(["${local.public_base_url}/api/login/oauth2/code/cognito"], var.cognito_extra_callback_urls)
  logout_urls   = length(var.cognito_extra_logout_urls) == 0 ? null : concat(["${local.public_base_url}/"], var.cognito_extra_logout_urls)
}
