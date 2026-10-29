# AWS 실배포 플랫폼 스택 (TASK-65, decision-11). 관리형 미사용 — k3s 노드 1대(t4g.large 스팟)가 DB·Kafka·MinIO·모니터링을 모두 돌린다.
# 레거시 infra/main 은 TASK-52 AC #5(철거)까지 그대로 두고, 여기는 apply 하지 않고 tftest(mock_provider)로만 검증한다.

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
}
