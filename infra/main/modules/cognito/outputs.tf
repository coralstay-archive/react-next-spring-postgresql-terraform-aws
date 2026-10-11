output "user_pool_id" {
  description = "User Pool ID (<region>_xxxx)"
  value       = aws_cognito_user_pool.this.id
}

output "user_pool_arn" {
  description = "User Pool ARN"
  value       = aws_cognito_user_pool.this.arn
}

output "issuer_uri" {
  description = "OIDC issuer → 앱 env COGNITO_ISSUER_URI. id_token 의 iss 와 같고 JWKS 는 <issuer>/.well-known/jwks.json"
  value       = "https://cognito-idp.${local.region}.amazonaws.com/${aws_cognito_user_pool.this.id}"
}

output "client_id" {
  description = "App Client ID → 앱 env COGNITO_CLIENT_ID"
  value       = aws_cognito_user_pool_client.this.id
}

output "client_secret" {
  description = "App Client secret → 앱 env COGNITO_CLIENT_SECRET (SSM SecureString 으로 옮긴다)"
  value       = aws_cognito_user_pool_client.this.client_secret
  sensitive   = true
}

output "domain_url" {
  description = "Hosted UI 주소 → 앱 env COGNITO_DOMAIN (/oauth2/authorize, /oauth2/token, /oauth2/userInfo, /logout)"
  value       = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${local.region}.amazoncognito.com"
}

output "callback_urls" {
  description = "App Client 에 등록된 콜백 URL"
  value       = local.callback_urls
}

output "logout_urls" {
  description = "App Client 에 등록된 로그아웃 URL"
  value       = local.logout_urls
}
