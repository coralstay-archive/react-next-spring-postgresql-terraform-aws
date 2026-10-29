output "instance_id" {
  description = "LB instance ID"
  value       = aws_instance.this.id
}

output "public_ip" {
  description = "LB EIP"
  value       = aws_eip.this.public_ip
}

output "backend_ip" {
  description = "넘기는 곳(k3s 노드 사설 IP)"
  value       = var.backend_ip
}

output "ami_id" {
  description = "LB AMI"
  value       = aws_instance.this.ami
}
