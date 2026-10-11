# 최소 네트워크 (TASK-16#2): VPC 1개 + public subnet 1개 + IGW. NAT·private subnet 은 비용 때문에 두지 않는다.
# EC2 는 public subnet 에 있고 SG 로 CloudFront origin-facing prefix list 에서만 8080 을 받는다.

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true # EIP public DNS(ec2-x-x-x-x.compute-1.amazonaws.com) → CloudFront custom origin

  tags = merge(var.tags, { Name = var.name })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = var.name })
}

resource "aws_subnet" "public" {
  vpc_id            = aws_vpc.this.id
  cidr_block        = var.public_subnet_cidr
  availability_zone = var.availability_zone

  # EIP 가 붙기 전 첫 부팅(user_data 의 dnf·compose 다운로드)에도 인터넷이 되도록. EIP 연결 후에는 EIP 로 바뀐다.
  map_public_ip_on_launch = true

  tags = merge(var.tags, { Name = "${var.name}-public" })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-public" })
}

resource "aws_route" "internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}
