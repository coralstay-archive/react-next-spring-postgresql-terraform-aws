# TASK-16#2 network 모듈 테스트: VPC 1개, public subnet 1개(AZ 변수), IGW, 기본 경로. mock_provider(오프라인).

mock_provider "aws" {}

variables {
  name              = "board-test"
  availability_zone = "us-east-1a"
}

# TASK-16#2 VPC: EIP public DNS 가 생기려면 DNS hostnames 가 켜져 있어야 한다(CloudFront origin 은 DNS 이름이 필요)
run "vpc" {
  command = apply

  assert {
    condition     = aws_vpc.this.cidr_block == "10.20.0.0/16"
    error_message = "VPC CIDR 기본값 10.20.0.0/16"
  }
  assert {
    condition     = aws_vpc.this.enable_dns_hostnames && aws_vpc.this.enable_dns_support
    error_message = "DNS hostnames·support 켬(EIP public DNS → CloudFront origin)"
  }
}

# TASK-16#2 public subnet 하나: 지정한 AZ, 0.0.0.0/0 → IGW
run "public_subnet" {
  command = apply

  assert {
    condition     = aws_subnet.public.availability_zone == "us-east-1a" && aws_subnet.public.cidr_block == "10.20.1.0/24"
    error_message = "subnet 은 var.availability_zone 에 10.20.1.0/24"
  }
  assert {
    condition     = aws_subnet.public.vpc_id == aws_vpc.this.id
    error_message = "subnet 은 이 VPC 안"
  }
  assert {
    condition     = aws_subnet.public.map_public_ip_on_launch
    error_message = "EIP 연결 전 첫 부팅(user_data 패키지 설치)에도 인터넷이 되도록 퍼블릭 IP 자동 할당"
  }
  assert {
    condition     = aws_route.internet.destination_cidr_block == "0.0.0.0/0" && aws_route.internet.gateway_id == aws_internet_gateway.this.id
    error_message = "기본 경로는 IGW"
  }
  assert {
    condition     = aws_route_table_association.public.subnet_id == aws_subnet.public.id && aws_route_table_association.public.route_table_id == aws_route_table.public.id
    error_message = "subnet 은 public route table 에 연결"
  }
  assert {
    condition     = output.subnet_id == aws_subnet.public.id && output.vpc_id == aws_vpc.this.id && output.availability_zone == "us-east-1a"
    error_message = "출력: vpc_id, subnet_id, availability_zone"
  }
}

run "subnet_outside_vpc_rejected" {
  command = plan

  variables {
    public_subnet_cidr = "10.99.1.0/24"
  }

  expect_failures = [var.public_subnet_cidr]
}
