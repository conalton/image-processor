output "function_name" {
  description = "Name of the function."
  value       = aws_lambda_function.this.function_name
}

output "log_group_name" {
  description = "Name of the function's log group."
  value       = aws_cloudwatch_log_group.this.name
}
