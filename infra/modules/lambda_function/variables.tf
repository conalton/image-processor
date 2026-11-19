variable "name" {
  description = "Name of the function; the role has the same name and the log group is /aws/lambda/<name>."
  type        = string
}

variable "handler" {
  description = "Fully qualified handler class."
  type        = string
}

variable "runtime" {
  description = "Lambda runtime."
  type        = string
}

variable "architecture" {
  description = "Instruction set architecture of the function."
  type        = string
}

variable "memory_mb" {
  description = "Memory of the function in MB."
  type        = number
}

variable "timeout_seconds" {
  description = "Timeout of the function in seconds."
  type        = number
}

variable "zip_path" {
  description = "Path of the deployment package; it must exist when the plan is made."
  type        = string
}

variable "log_retention_days" {
  description = "Days the function's log events are kept."
  type        = number
}

variable "environment" {
  description = "Environment variables of the function."
  type        = map(string)
}

variable "policy_json" {
  description = "IAM policy document with the function's own permissions; the module adds the permission to write its log group."
  type        = string
}

variable "sqs_trigger" {
  description = "SQS queue that invokes the function, or null for none."
  type = object({
    queue_arn           = string
    batch_size          = number
    maximum_concurrency = number
  })
  default = null
}
