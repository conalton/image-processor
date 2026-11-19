locals {
  zip_exists = fileexists(var.zip_path)
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/aws/lambda/${var.name}"
  retention_in_days = var.log_retention_days
}

data "aws_iam_policy_document" "assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "access" {
  source_policy_documents = [var.policy_json]

  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.this.arn}:*"]
  }
}

resource "aws_iam_role" "this" {
  name               = var.name
  assume_role_policy = data.aws_iam_policy_document.assume_role.json
}

resource "aws_iam_role_policy" "this" {
  name   = "access"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.access.json
}

resource "aws_lambda_function" "this" {
  function_name    = var.name
  role             = aws_iam_role.this.arn
  runtime          = var.runtime
  handler          = var.handler
  architectures    = [var.architecture]
  memory_size      = var.memory_mb
  timeout          = var.timeout_seconds
  filename         = var.zip_path
  source_code_hash = local.zip_exists ? filebase64sha256(var.zip_path) : null

  logging_config {
    log_format = "JSON"
    log_group  = aws_cloudwatch_log_group.this.name
  }

  environment {
    variables = var.environment
  }

  lifecycle {
    precondition {
      condition     = local.zip_exists
      error_message = "${var.zip_path} is missing: run 'make build'."
    }
  }
}

resource "aws_lambda_event_source_mapping" "sqs" {
  count = var.sqs_trigger == null ? 0 : 1

  event_source_arn = var.sqs_trigger.queue_arn
  function_name    = aws_lambda_function.this.arn
  batch_size       = var.sqs_trigger.batch_size

  scaling_config {
    maximum_concurrency = var.sqs_trigger.maximum_concurrency
  }

  # AWS checks the role's queue permissions when it creates the mapping.
  depends_on = [aws_iam_role_policy.this]
}
