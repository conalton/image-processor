locals {
  images_bucket_name = "${var.name}-${var.account_id}-${var.aws_region}"
}

resource "aws_s3_bucket" "images" {
  bucket = local.images_bucket_name
  # Sandbox: the stack is destroyed with everything in it.
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "images" {
  bucket = aws_s3_bucket.images.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "images" {
  bucket = aws_s3_bucket.images.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

data "aws_iam_policy_document" "images_tls_only" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.images.arn, "${aws_s3_bucket.images.arn}/*"]

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

resource "aws_s3_bucket_policy" "images" {
  bucket = aws_s3_bucket.images.id
  policy = data.aws_iam_policy_document.images_tls_only.json

  # AWS checks the bucket policy against the public access block when it creates it.
  depends_on = [aws_s3_bucket_public_access_block.images]
}

resource "aws_s3_bucket_lifecycle_configuration" "images" {
  bucket = aws_s3_bucket.images.id

  rule {
    id     = "expire"
    status = "Enabled"

    filter {}

    expiration {
      days = var.retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_cors_configuration" "images" {
  bucket = aws_s3_bucket.images.id

  cors_rule {
    allowed_methods = ["PUT"]
    allowed_headers = ["Content-Type", "If-None-Match", "Content-Disposition", "Cache-Control"]
    allowed_origins = [local.page_origin]
  }
}

resource "aws_s3_bucket_notification" "originals" {
  bucket = aws_s3_bucket.images.id

  queue {
    queue_arn     = aws_sqs_queue.images.arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = local.originals_prefix
  }

  # AWS checks that the queue policy lets S3 send messages when it creates the notification.
  depends_on = [aws_sqs_queue_policy.images]
}
