resource "aws_dynamodb_table" "tasks" {
  name         = "${var.name}-tasks"
  billing_mode = "PAY_PER_REQUEST"
  # Kotlin: ID in TaskItemAttributes.kt.
  hash_key = "id"

  attribute {
    name = "id"
    type = "S"
  }

  ttl {
    # Kotlin: EXPIRES_AT in TaskItemAttributes.kt.
    attribute_name = "expiresAt"
    enabled        = true
  }
}
