package imageprocessor.worker.deadletter

import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.logging.LogLevel

fun logDeadLetter(logger: LambdaLogger, messageId: String, outcome: DeadLetterOutcome) {
    when (outcome) {
        is DeadLetterOutcome.Unreadable -> {
            val trace = outcome.cause.stackTraceToString()
            logger.log("Message $messageId is not an S3 notification: acknowledged\n$trace", LogLevel.ERROR)
        }

        is DeadLetterOutcome.Read -> {
            if (outcome.marked.isEmpty() && outcome.skipped.isEmpty()) {
                logger.log("Message $messageId names no original: ignored", LogLevel.INFO)
            }
            for (id in outcome.marked) {
                logger.log("Task $id marked FAILED: its message reached the dead-letter queue", LogLevel.ERROR)
            }
            for (id in outcome.skipped) {
                logger.log("Task $id not marked: it is finished, already failed or unknown", LogLevel.INFO)
            }
        }
    }
}
