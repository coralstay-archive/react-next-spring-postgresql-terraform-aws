output "instance_id" {
  description = "노드 instance ID (SSM 접속·send-command 대상)"
  value       = aws_instance.this.id
}

output "instance_type" {
  description = "노드 타입"
  value       = aws_instance.this.instance_type
}

output "ami_id" {
  description = "노드 AMI (lb_node 가 같은 이미지를 쓴다)"
  value       = data.aws_ami.rocky.id
}

output "subnet_id" {
  description = "노드 subnet"
  value       = aws_instance.this.subnet_id
}

output "availability_zone" {
  description = "노드 AZ"
  value       = aws_instance.this.availability_zone
}

output "private_ip" {
  description = "노드 사설 IP (lb_node 의 backend)"
  value       = aws_instance.this.private_ip
}

output "public_ip" {
  description = "EIP"
  value       = aws_eip.this.public_ip
}

output "security_group_id" {
  description = "노드 SG"
  value       = aws_security_group.this.id
}

output "role_name" {
  description = "노드 role 이름"
  value       = aws_iam_role.this.name
}

output "data_volume_id" {
  description = "데이터 볼륨 ID"
  value       = aws_ebs_volume.data.id
}

output "data_mount_path" {
  description = "데이터 볼륨 마운트 경로"
  value       = var.data_mount_path
}

output "snapshot_tag" {
  description = "데이터 볼륨 스냅샷(DLM) 대상 태그"
  value       = local.snapshot_tag
}
