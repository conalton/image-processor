package imageprocessor.worker.processing

import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.logging.LogLevel

private const val MAX_DETAIL_CHARS = 200
private val LINE_BREAKS_AND_CONTROLS = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")

fun logOutcome(logger: LambdaLogger, id: String, outcome: ProcessingOutcome) {
    when (outcome) {
        is ProcessingOutcome.Skipped ->
            logger.log("Task $id skipped: ${outcome.reason}", LogLevel.INFO)

        is ProcessingOutcome.Done ->
            logger.log("Task $id done with ${outcome.variants.size} variants", LogLevel.INFO)

        is ProcessingOutcome.InputRejected -> {
            val detail = outcome.detail?.let { text -> ": ${oneLine(text)}" }.orEmpty()
            logger.log("Task $id input rejected: ${outcome.code}$detail", LogLevel.INFO)
        }

        is ProcessingOutcome.Failed -> {
            val trace = outcome.cause.stackTraceToString()
            logger.log("Task $id failed: ${outcome.code} at ${outcome.stage}\n$trace", LogLevel.ERROR)
        }
    }
}

// The detail comes from reading an uploaded file: one line and bounded.
private fun oneLine(text: String): String = text.replace(LINE_BREAKS_AND_CONTROLS, " ").take(MAX_DETAIL_CHARS)
