data "aws_iam_policy_document" "api" {
  statement {
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem"]
    resources = [aws_dynamodb_table.tasks.arn]
  }

  statement {
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.images.arn}/${local.originals_prefix}*"]
  }

  # The view and download links are presigned with this role.
  statement {
    actions = ["s3:GetObject"]
    resources = [
      "${aws_s3_bucket.images.arn}/${local.originals_prefix}*",
      "${aws_s3_bucket.images.arn}/${local.derived_prefix}*",
    ]
  }
}

module "api" {
  source = "./modules/lambda_function"

  name               = "${var.name}-api"
  handler            = "imageprocessor.api.ApiHandler"
  runtime            = var.lambda_runtime
  architecture       = var.lambda_architecture
  memory_mb          = var.api_memory_mb
  timeout_seconds    = var.api_timeout_seconds
  zip_path           = local.api_zip
  log_retention_days = var.log_retention_days
  policy_json        = data.aws_iam_policy_document.api.json

  # Read by ApiConfig and TaskTableConfig.
  environment = {
    IMAGES_BUCKET       = aws_s3_bucket.images.id
    ORIGINALS_PREFIX    = local.originals_prefix
    S3_PUBLIC_ENDPOINT  = local.s3_public_endpoint
    TASKS_TABLE         = aws_dynamodb_table.tasks.name
    RETENTION_DAYS      = var.retention_days
    SDK_CALL_TIMEOUT_MS = local.api_sdk_call_timeout_ms
  }
}

resource "aws_lambda_function_url" "api" {
  function_name = module.api.function_name
  # Public and not throttled on purpose: a Function URL throttles only through reserved concurrency.
  authorization_type = "NONE"

  cors {
    allow_origins = [local.page_origin]
    allow_methods = ["GET", "POST"]
    allow_headers = ["content-type"]
  }
}

resource "aws_lambda_permission" "api_url" {
  statement_id           = "AllowInvokeFunctionUrl"
  function_name          = module.api.function_name
  action                 = "lambda:InvokeFunctionUrl"
  principal              = "*"
  function_url_auth_type = aws_lambda_function_url.api.authorization_type
}

# A function URL with auth NONE needs lambda:InvokeFunction as well as lambda:InvokeFunctionUrl (AWS, "Control access
# to Lambda function URLs").
resource "aws_lambda_permission" "api_invoke" {
  statement_id             = "AllowInvokeViaFunctionUrl"
  function_name            = module.api.function_name
  action                   = "lambda:InvokeFunction"
  principal                = "*"
  invoked_via_function_url = true
}
