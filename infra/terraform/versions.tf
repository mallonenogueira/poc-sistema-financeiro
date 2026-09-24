terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.70"
    }
  }

  # Em times reais: backend remoto com lock (S3 + DynamoDB).
  # backend "s3" {
  #   bucket         = "pocbank-terraform-state"
  #   key            = "pocbank/terraform.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "pocbank-terraform-lock"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "pocbank"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
