# 사용자 도메인 (TASK-16#5, decision-9). 루트 스택은 domain_name 이 있을 때만 이 모듈을 켠다(count).
# Route53 zone(조회 또는 생성) + ACM(us-east-1, DNS 검증) + CloudFront alias A/AAAA.
# CloudFront 인증서는 us-east-1 ACM 에만 둘 수 있다. 이 모듈은 provider 하나만 쓰고, 루트가 providers = { aws = aws.us_east_1 } 로
# 넘긴다(Route53 은 전역이라 같은 provider 로 충분). configuration_aliases 를 쓰면 모듈 단독 validate 가 안 돼서 이렇게 둔다.
# 순서: 인증서 → 검증 레코드 → 검증 완료 → (frontend_cdn) 배포 aliases·인증서 → alias 레코드. 리소스 단위 의존이라 순환이 없다.

locals {
  zone_name = coalesce(var.zone_name, var.domain_name)
  names     = distinct(concat([var.domain_name], var.subject_alternative_names))
  zone_id   = var.create_hosted_zone ? aws_route53_zone.this[0].zone_id : data.aws_route53_zone.existing[0].zone_id

  alias_records = merge([
    for n in local.names : { for t in ["A", "AAAA"] : "${n}/${t}" => { name = n, type = t } }
  ]...)
}

data "aws_route53_zone" "existing" {
  count = var.create_hosted_zone ? 0 : 1

  name         = local.zone_name
  private_zone = false
}

resource "aws_route53_zone" "this" {
  count = var.create_hosted_zone ? 1 : 0

  name = local.zone_name
  tags = var.tags
}

resource "aws_acm_certificate" "this" {
  domain_name               = var.domain_name
  subject_alternative_names = var.subject_alternative_names
  validation_method         = "DNS"
  tags                      = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

# for_each 키는 설정에서 아는 이름으로 둔다(domain_validation_options 는 apply 전까지 모를 수 있다)
resource "aws_route53_record" "validation" {
  for_each = toset(local.names)

  zone_id         = local.zone_id
  name            = one([for o in aws_acm_certificate.this.domain_validation_options : o.resource_record_name if o.domain_name == each.key])
  type            = one([for o in aws_acm_certificate.this.domain_validation_options : o.resource_record_type if o.domain_name == each.key])
  records         = [one([for o in aws_acm_certificate.this.domain_validation_options : o.resource_record_value if o.domain_name == each.key])]
  ttl             = 300
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "this" {
  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [for r in aws_route53_record.validation : r.name]
}

resource "aws_route53_record" "alias" {
  for_each = local.alias_records

  zone_id = local.zone_id
  name    = each.value.name
  type    = each.value.type

  alias {
    name                   = var.cloudfront_domain_name
    zone_id                = var.cloudfront_hosted_zone_id
    evaluate_target_health = false
  }
}
