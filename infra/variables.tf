# On LocalStack the page origin built from it is also in compose.yaml (EXTRA_CORS_ALLOWED_ORIGINS); a mismatch makes
# make smoke-localstack fail with 403.
variable "name" {
  description = "Name prefix of the resources and value of the Project tag."
  type        = string
  default     = "image-processor"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}$", var.name))
    error_message = "Use 2 to 31 lower-case letters, digits or hyphens, starting with a letter."
  }
}

variable "aws_region" {
  description = "AWS region of the stack."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.aws_region))
    error_message = "Use a region name such as eu-north-1."
  }
}

variable "account_id" {
  description = "Id of the AWS account the provider may use (000000000000 on LocalStack)."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "Use the 12 digits of the account id."
  }
}

variable "endpoint" {
  description = "Endpoint of LocalStack for all services; null on AWS."
  type        = string
  default     = null
}

variable "s3_use_path_style" {
  description = "Address buckets by path instead of by host name (LocalStack is reached as one host name)."
  type        = bool
  default     = false
}

variable "tag_resources" {
  description = "Tag all resources with the default tags; false on LocalStack, where DescribeAlarms fails for a tagged alarm."
  type        = bool
  default     = true
}

variable "s3_public_endpoint" {
  description = "S3 endpoint the browser uses; the API signs its upload, view and download URLs for this host. Set on LocalStack only; on AWS it is derived from aws_region."
  type        = string
  default     = null

  validation {
    condition     = var.s3_public_endpoint == null || can(regex("^https?://[^/]+$", var.s3_public_endpoint))
    error_message = "Use an endpoint without a path, such as http://s3.localhost:4566."
  }

  validation {
    condition     = (var.endpoint == null) == (var.s3_public_endpoint == null)
    error_message = "Set s3_public_endpoint together with endpoint (LocalStack) and not on AWS, where it is derived from aws_region."
  }
}

variable "lambda_runtime" {
  description = "Lambda runtime of the three functions, for example java21; versions.env owns the value."
  type        = string
}

variable "lambda_architecture" {
  description = "Architecture of all functions; the zips have no native code."
  type        = string

  validation {
    condition     = contains(["x86_64", "arm64"], var.lambda_architecture)
    error_message = "Use x86_64 or arm64."
  }
}

variable "api_timeout_seconds" {
  description = "Timeout of the API function; a whole number of 1 to 900 seconds."
  type        = number
  default     = 10

  validation {
    condition     = var.api_timeout_seconds >= 1 && var.api_timeout_seconds <= 900 && var.api_timeout_seconds == floor(var.api_timeout_seconds)
    error_message = "Use a whole number of 1 to 900 seconds."
  }
}

variable "api_memory_mb" {
  description = "Memory of the API function; Lambda accepts a whole number of 128 to 10240 MB."
  type        = number
  default     = 1769

  validation {
    condition     = var.api_memory_mb >= 128 && var.api_memory_mb <= 10240 && var.api_memory_mb == floor(var.api_memory_mb)
    error_message = "Use a whole number of 128 to 10240 MB."
  }
}

variable "worker_timeout_seconds" {
  description = "Timeout of the worker function; the queue visibility timeout is derived from it; a whole number of 1 to 900 seconds."
  type        = number
  default     = 23

  validation {
    condition     = var.worker_timeout_seconds >= 1 && var.worker_timeout_seconds <= 900 && var.worker_timeout_seconds == floor(var.worker_timeout_seconds)
    error_message = "Use a whole number of 1 to 900 seconds."
  }
}

variable "worker_memory_mb" {
  description = "Memory of the worker function; Lambda accepts a whole number of 128 to 10240 MB."
  type        = number
  default     = 1769

  validation {
    condition     = var.worker_memory_mb >= 128 && var.worker_memory_mb <= 10240 && var.worker_memory_mb == floor(var.worker_memory_mb)
    error_message = "Use a whole number of 128 to 10240 MB."
  }
}

variable "worker_max_concurrency" {
  description = "Maximum number of worker invocations the queue mapping runs at once; a whole number of 2 to 1000."
  type        = number
  default     = 2

  validation {
    condition     = var.worker_max_concurrency >= 2 && var.worker_max_concurrency <= 1000 && var.worker_max_concurrency == floor(var.worker_max_concurrency)
    error_message = "An SQS event source mapping accepts a whole number of 2 to 1000."
  }
}

variable "retention_days" {
  description = "Days a task is kept: its DynamoDB item expires and its S3 objects are deleted after this time."
  type        = number
  default     = 7

  validation {
    condition     = var.retention_days >= 1 && var.retention_days == floor(var.retention_days)
    error_message = "Use a whole number of days, at least 1."
  }
}

variable "log_retention_days" {
  description = "Retention of the function log groups; CloudWatch Logs accepts only its fixed periods (1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653 days)."
  type        = number
  default     = 7
}

variable "alarm_email" {
  description = "E-mail address that receives the alarm notifications; null creates no subscription. AWS sends a confirmation link to it."
  type        = string
  default     = null
  sensitive   = true

  validation {
    condition     = var.alarm_email == null || can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.alarm_email))
    error_message = "Use an address such as you@example.com."
  }
}
