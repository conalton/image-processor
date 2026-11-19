output "function_url" {
  description = "Address of the API only; the page is at page_url."
  value       = aws_lambda_function_url.api.function_url
}

output "page_url" {
  description = "Address of the page: the bucket is served from its REST endpoint, which has no index document, so the file name is part of the address."
  value       = "${local.page_origin}/index.html"
}

output "api_log_group" {
  description = "Log group of the API function."
  value       = module.api.log_group_name
}

output "worker_log_group" {
  description = "Log group of the worker function."
  value       = module.worker.log_group_name
}

output "dead_letter_log_group" {
  description = "Log group of the dead-letter consumer function."
  value       = module.dead_letter.log_group_name
}

output "aws_region" {
  description = "Region of the stack; make cli-aws reads it."
  value       = var.aws_region
}
