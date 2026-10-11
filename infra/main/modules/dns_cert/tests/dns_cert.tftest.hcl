# TASK-16#5 dns_cert 모듈 테스트: Route53 hosted zone(조회 또는 생성) + ACM(us-east-1, DNS 검증) + CloudFront alias A/AAAA.
# 루트 스택은 domain_name 이 비어 있으면 이 모듈을 count = 0 으로 끈다(decision-9). mock_provider(오프라인).

# 루트는 이 모듈에 aws.us_east_1 을 넘긴다. 테스트에서는 모듈의 기본 aws 가 그 역할이다.
mock_provider "aws" {
  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:us-east-1:123456789012:certificate/test"
    }
  }
  mock_resource "aws_acm_certificate_validation" {
    defaults = {
      certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/test"
    }
  }
}

override_data {
  target = data.aws_route53_zone.existing
  values = {
    zone_id = "Z0EXISTING"
  }
}

override_resource {
  target = aws_acm_certificate.this
  values = {
    arn = "arn:aws:acm:us-east-1:123456789012:certificate/test"
    domain_validation_options = [
      {
        domain_name           = "board.example.com"
        resource_record_name  = "_abc.board.example.com."
        resource_record_type  = "CNAME"
        resource_record_value = "_xyz.acm-validations.aws."
      },
      {
        domain_name           = "www.board.example.com"
        resource_record_name  = "_def.www.board.example.com."
        resource_record_type  = "CNAME"
        resource_record_value = "_uvw.acm-validations.aws."
      },
    ]
  }
}

variables {
  domain_name               = "board.example.com"
  subject_alternative_names = ["www.board.example.com"]
  zone_name                 = "example.com"
  cloudfront_domain_name    = "d111.cloudfront.net"
  cloudfront_hosted_zone_id = "Z2FDTNDATAQYW2"
}

# TASK-16#5 ACM: us-east-1(CloudFront 요구), DNS 검증, 도메인 + SAN
run "certificate" {
  command = apply

  assert {
    condition     = aws_acm_certificate.this.domain_name == "board.example.com" && aws_acm_certificate.this.validation_method == "DNS"
    error_message = "도메인 인증서, DNS 검증"
  }
  assert {
    condition     = toset(aws_acm_certificate.this.subject_alternative_names) == toset(["www.board.example.com"])
    error_message = "SAN"
  }
  assert {
    condition     = output.certificate_arn == "arn:aws:acm:us-east-1:123456789012:certificate/test"
    error_message = "검증 완료된 인증서 ARN 출력(CloudFront viewer_certificate)"
  }
  assert {
    condition     = toset(output.aliases) == toset(["board.example.com", "www.board.example.com"])
    error_message = "CloudFront aliases 출력"
  }
}

# TASK-16#5 DNS 검증 레코드: 이름마다 CNAME 을 기존 zone 에 만들고 검증 리소스가 기다린다
run "validation_records" {
  command = apply

  assert {
    condition     = length(aws_route53_record.validation) == 2
    error_message = "도메인·SAN 마다 검증 레코드"
  }
  assert {
    condition = (
      aws_route53_record.validation["board.example.com"].name == "_abc.board.example.com."
      && aws_route53_record.validation["board.example.com"].type == "CNAME"
      && toset(aws_route53_record.validation["board.example.com"].records) == toset(["_xyz.acm-validations.aws."])
      && aws_route53_record.validation["board.example.com"].zone_id == "Z0EXISTING"
    )
    error_message = "ACM 이 준 CNAME 을 그대로"
  }
  assert {
    condition     = toset(aws_acm_certificate_validation.this.validation_record_fqdns) == toset(["_abc.board.example.com.", "_def.www.board.example.com."])
    error_message = "검증 리소스는 검증 레코드를 기다린다"
  }
  assert {
    condition     = length(aws_route53_zone.this) == 0 && output.zone_id == "Z0EXISTING"
    error_message = "기본은 기존 hosted zone 을 조회(도메인 등록 시 Route53 이 만든 zone)"
  }
}

# TASK-16#5 CloudFront alias: 이름마다 A·AAAA(IPv6) alias 레코드
run "alias_records" {
  command = apply

  assert {
    condition     = length(aws_route53_record.alias) == 4
    error_message = "2개 이름 × A·AAAA"
  }
  assert {
    condition = alltrue([
      for r in aws_route53_record.alias :
      one(r.alias).name == "d111.cloudfront.net" && one(r.alias).zone_id == "Z2FDTNDATAQYW2" && !one(r.alias).evaluate_target_health
    ])
    error_message = "CloudFront 배포로 alias"
  }
  assert {
    condition     = toset([for r in aws_route53_record.alias : "${r.name}/${r.type}"]) == toset(["board.example.com/A", "board.example.com/AAAA", "www.board.example.com/A", "www.board.example.com/AAAA"])
    error_message = "A·AAAA 모두"
  }
}

# TASK-16#5 hosted zone 을 새로 만들 수도 있다(외부 등록기관 도메인 → NS 위임)
run "create_zone" {
  command = apply

  variables {
    create_hosted_zone = true
  }

  assert {
    condition     = length(aws_route53_zone.this) == 1 && aws_route53_zone.this[0].name == "example.com"
    error_message = "zone 생성"
  }
  assert {
    condition     = output.zone_id == aws_route53_zone.this[0].zone_id
    error_message = "생성한 zone 사용"
  }
}

run "empty_domain_rejected" {
  command = plan

  variables {
    domain_name = ""
  }

  expect_failures = [var.domain_name]
}
