resource "aws_sqs_queue" "dead_letter" {
  name = "${var.name}-images-dlq"
  # Expiry counts from the first send to the images queue (AWS). A message may outlive its task: the conditional write
  # skips a deleted item, and an expired one not yet deleted gets FAILED, which reads treat as not found.
  message_retention_seconds  = local.queue_retention_seconds
  visibility_timeout_seconds = local.dead_letter_visibility_timeout_seconds
}

resource "aws_sqs_queue" "images" {
  name                       = "${var.name}-images"
  message_retention_seconds  = local.queue_retention_seconds
  visibility_timeout_seconds = local.queue_visibility_timeout_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter.arn
    # AWS recommends at least 5.
    maxReceiveCount = local.max_receive_count
  })
}

data "aws_iam_policy_document" "queue_from_bucket" {
  statement {
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.images.arn]

    principals {
      type        = "Service"
      identifiers = ["s3.amazonaws.com"]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_s3_bucket.images.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_sqs_queue_policy" "images" {
  queue_url = aws_sqs_queue.images.id
  policy    = data.aws_iam_policy_document.queue_from_bucket.json
}
