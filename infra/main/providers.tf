locals {
  default_tags = {
    Project     = "board"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.default_tags
  }
}

# CloudFront 인증서(ACM)는 us-east-1 에만 둘 수 있다. 지금은 var.region 도 us-east-1 이지만 리전을 바꿔도 깨지지 않게 따로 둔다.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = local.default_tags
  }
}
