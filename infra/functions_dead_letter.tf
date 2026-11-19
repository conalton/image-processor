data "aws_iam_policy_document" "dead_letter" {
  statement {
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.dead_letter.arn]
  }

  statement {
    actions   = ["dynamodb:UpdateItem"]
    resources = [aws_dynamodb_table.tasks.arn]
  }
}

module "dead_letter" {
  source = "./modules/lambda_function"

  name               = "${var.name}-dead-letter"
  handler            = "imageprocessor.worker.deadletter.DeadLetterHandler"
  runtime            = var.lambda_runtime
  architecture       = var.lambda_architecture
  memory_mb          = 512
  timeout_seconds    = local.dead_letter_timeout_seconds
  zip_path           = local.worker_zip
  log_retention_days = var.log_retention_days
  policy_json        = data.aws_iam_policy_document.dead_letter.json

  # Read by DeadLetterConfig and TaskTableConfig.
  environment = {
    ORIGINALS_PREFIX = local.originals_prefix
    TASKS_TABLE      = aws_dynamodb_table.tasks.name
  }

  sqs_trigger = {
    queue_arn           = aws_sqs_queue.dead_letter.arn
    batch_size          = 1
    maximum_concurrency = 2
  }
}
