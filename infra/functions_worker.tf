data "aws_iam_policy_document" "worker" {
  statement {
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.images.arn]
  }

  statement {
    actions   = ["dynamodb:GetItem", "dynamodb:UpdateItem"]
    resources = [aws_dynamodb_table.tasks.arn]
  }

  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.images.arn}/${local.originals_prefix}*"]
  }

  statement {
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.images.arn}/${local.derived_prefix}*"]
  }

  # Without it S3 answers 403 instead of NoSuchKey for a missing original. A GetObject has no s3:prefix to match.
  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.images.arn]
  }
}

module "worker" {
  source = "./modules/lambda_function"

  name               = "${var.name}-worker"
  handler            = "imageprocessor.worker.WorkerHandler"
  runtime            = var.lambda_runtime
  architecture       = var.lambda_architecture
  memory_mb          = var.worker_memory_mb
  timeout_seconds    = var.worker_timeout_seconds
  zip_path           = local.worker_zip
  log_retention_days = var.log_retention_days
  policy_json        = data.aws_iam_policy_document.worker.json

  # Read by WorkerConfig and TaskTableConfig.
  environment = {
    IMAGES_BUCKET    = aws_s3_bucket.images.id
    ORIGINALS_PREFIX = local.originals_prefix
    DERIVED_PREFIX   = local.derived_prefix
    TASKS_TABLE      = aws_dynamodb_table.tasks.name
  }

  sqs_trigger = {
    # The handler fails a whole batch: later messages would not run, and every one would count a failed receive.
    batch_size          = 1
    queue_arn           = aws_sqs_queue.images.arn
    maximum_concurrency = var.worker_max_concurrency
  }
}
