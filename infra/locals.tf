locals {
  originals_prefix = "originals/"
  derived_prefix   = "derived/"

  # AWS asks for a visibility timeout of at least six times the function timeout, for retries of throttled messages.
  # queue_retry_budget_seconds: time until a failing message reaches the dead-letter queue with back-to-back receives;
  # the page gets it in config.json.
  max_receive_count                      = 5
  visibility_timeout_factor              = 6
  queue_visibility_timeout_seconds       = local.visibility_timeout_factor * var.worker_timeout_seconds
  queue_retry_budget_seconds             = local.max_receive_count * local.queue_visibility_timeout_seconds
  dead_letter_timeout_seconds            = 10
  dead_letter_visibility_timeout_seconds = local.visibility_timeout_factor * local.dead_letter_timeout_seconds

  # SQS maximum (14 days). The default of 4 days is shorter than a task lives (var.retention_days):
  # a worker down for more than 4 days would lose messages.
  queue_retention_seconds = 14 * 24 * 60 * 60

  # Below the function timeout: the API then answers the contract's 500, not the Function URL's 502.
  api_sdk_call_timeout_ms = floor(var.api_timeout_seconds * 700)

  s3_public_endpoint = coalesce(var.s3_public_endpoint, "https://s3.${var.aws_region}.amazonaws.com")

  api_zip    = "${path.module}/../backend/api/build/distributions/api.zip"
  worker_zip = "${path.module}/../backend/worker/build/distributions/worker.zip"
}
