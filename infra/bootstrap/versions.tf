terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # bootstrap 자신은 로컬 state 로 한 번만 apply 한다(README). state 버킷이 생기기 전이라 원격 backend 를 쓸 수 없다.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "board"
      ManagedBy = "terraform"
      Stack     = "bootstrap"
    }
  }
}
