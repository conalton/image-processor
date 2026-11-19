locals {
  web_bucket_name = "${local.images_bucket_name}-web"
  page_origin     = replace(local.s3_public_endpoint, "://", "://${aws_s3_bucket.web.id}.")

  web_dist        = "${path.module}/../frontend/dist"
  web_dist_exists = fileexists("${local.web_dist}/index.html")
  # A file type that is not listed here fails `terraform plan` instead of being guessed.
  web_content_types = {
    html = "text/html; charset=utf-8"
  }
  web_cache_revalidate = "no-cache"
}

resource "aws_s3_bucket" "web" {
  bucket        = local.web_bucket_name
  force_destroy = true

  lifecycle {
    precondition {
      condition     = length(local.web_bucket_name) <= 63
      error_message = "The bucket name ${local.web_bucket_name} is longer than the 63 characters S3 allows: shorten the variable name."
    }
  }
}

resource "aws_s3_bucket_ownership_controls" "web" {
  bucket = aws_s3_bucket.web.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# The page is public through its bucket policy: this is the only bucket that does not block public policies. ACLs stay
# blocked (BucketOwnerEnforced disables them anyway).
resource "aws_s3_bucket_public_access_block" "web" {
  bucket = aws_s3_bucket.web.id

  block_public_acls       = true
  block_public_policy     = false
  ignore_public_acls      = true
  restrict_public_buckets = false
}

# No s3:ListBucket: a visitor can read a file whose name they know, not list the bucket.
data "aws_iam_policy_document" "web_public_read" {
  statement {
    sid       = "PublicRead"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.web.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.web.arn, "${aws_s3_bucket.web.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id
  policy = data.aws_iam_policy_document.web_public_read.json

  # AWS checks the bucket policy against the public access block when it creates it.
  depends_on = [aws_s3_bucket_public_access_block.web]
}

# frontend/dist is a build artifact, like the zips of the functions.
resource "aws_s3_object" "page" {
  for_each = fileset(local.web_dist, "**")

  bucket        = aws_s3_bucket.web.id
  key           = each.value
  source        = "${local.web_dist}/${each.value}"
  source_hash   = filemd5("${local.web_dist}/${each.value}")
  content_type  = local.web_content_types[reverse(split(".", each.value))[0]]
  cache_control = local.web_cache_revalidate
}

# A static page has no environment variables: Terraform hands it its values in config.json.
resource "aws_s3_object" "config" {
  bucket = aws_s3_bucket.web.id
  key    = "config.json"
  content = jsonencode({
    apiUrl             = trimsuffix(aws_lambda_function_url.api.function_url, "/")
    retryBudgetSeconds = local.queue_retry_budget_seconds
  })
  content_type  = "application/json"
  cache_control = local.web_cache_revalidate

  lifecycle {
    # Not on page: without a build, page has no instances, so its precondition would never run.
    precondition {
      condition     = local.web_dist_exists
      error_message = "${local.web_dist}/index.html is missing: run 'make build'."
    }
  }
}
