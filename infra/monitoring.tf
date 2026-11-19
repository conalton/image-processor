resource "aws_cloudwatch_metric_alarm" "dead_letter_received" {
  alarm_name          = "${var.name}-images-dlq-received"
  alarm_description   = "A message reached the dead-letter queue: its task was marked FAILED or was already finished, the message was unreadable, or the consumer is retrying; read the dead-letter log group."
  namespace           = "AWS/SQS"
  metric_name         = "NumberOfMessagesReceived"
  statistic           = "Sum"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  period              = 300
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]

  dimensions = {
    QueueName = aws_sqs_queue.dead_letter.name
  }
}

# The dead-letter function empties the queue within seconds, so a message visible for two periods is not being read.
resource "aws_cloudwatch_metric_alarm" "dead_letter_unread" {
  alarm_name          = "${var.name}-images-dlq-unread"
  alarm_description   = "Messages wait in the dead-letter queue and nothing reads them: the dead-letter function's mapping is disabled or deleted, or the function cannot receive; their tasks stay unfinished until they expire."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  period              = 300
  evaluation_periods  = 2
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]

  dimensions = {
    QueueName = aws_sqs_queue.dead_letter.name
  }
}
