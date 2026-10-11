output "state_bucket" {
  description = "state 버킷 이름 → infra/main/envs/prod.backend.hcl 의 bucket"
  value       = aws_s3_bucket.state.bucket
}

output "backend_config" {
  description = "infra/main/envs/prod.backend.hcl 에 그대로 붙여 넣을 내용(terraform init -backend-config=envs/prod.backend.hcl)"
  value       = <<-EOT
    bucket       = "${var.state_bucket_name}"
    key          = "${var.state_key}"
    region       = "${var.region}"
    encrypt      = true
    use_lockfile = true
  EOT
}
