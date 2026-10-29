locals {
  default_tags = {
    Project     = "board"
    Environment = var.environment
    Stack       = "platform"
    ManagedBy   = "terraform"
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.default_tags
  }
}
