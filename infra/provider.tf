provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.account_id]

  s3_use_path_style = var.s3_use_path_style

  endpoints {
    cloudwatch = var.endpoint
    dynamodb   = var.endpoint
    iam        = var.endpoint
    lambda     = var.endpoint
    logs       = var.endpoint
    s3         = var.endpoint
    s3control  = var.endpoint
    sns        = var.endpoint
    sqs        = var.endpoint
    sts        = var.endpoint
  }

  default_tags {
    tags = var.tag_resources ? {
      Project   = var.name
      Stage     = terraform.workspace
      ManagedBy = "terraform"
    } : {}
  }
}
