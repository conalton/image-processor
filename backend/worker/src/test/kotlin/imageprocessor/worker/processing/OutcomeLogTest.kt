package imageprocessor.worker.processing

import com.amazonaws.services.lambda.runtime.LambdaLogger
import imageprocessor.shared.contract.models.InputRejectionCode
import kotlin.test.Test
import kotlin.test.assertEquals

class OutcomeLogTest {
    private class RecordingLogger : LambdaLogger {
        val records = mutableListOf<String>()

        override fun log(message: String) {
            records.add(message)
        }

        override fun log(message: ByteArray) {
            records.add(message.decodeToString())
        }
    }

    @Test
    fun `logs a rejection detail on one line and cuts it to 200 characters`() {
        val logger = RecordingLogger()
        val detail = "a\nb\r\u0085\u2028\u2029\u202E\u0000" + "x".repeat(500)

        logOutcome(logger, "t1", ProcessingOutcome.InputRejected(InputRejectionCode.UNDECODABLE_IMAGE, detail))

        val expected = "Task t1 input rejected: UNDECODABLE_IMAGE: a b" + " ".repeat(6) + "x".repeat(191)
        assertEquals(listOf(expected), logger.records)
    }
}
