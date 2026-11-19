package imageprocessor.api.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpecExamplesTest {
    private val reader = SpecReader(specPath())
    private val validator = ContractValidator(specPath())

    @Test
    fun `every request and response example in the spec is valid`() {
        val requests = reader.requestExamples()
        val responses = reader.responseExamples()
        assertTrue(requests.isNotEmpty(), "The spec has no request examples")
        assertTrue(responses.isNotEmpty(), "The spec has no response examples")

        val failures = mutableListOf<String>()
        for (example in requests) {
            val messages = validator.validateRequest(example.method, example.path, example.contentType, example.body)
            if (messages.isNotEmpty()) {
                failures.add("${example.description}: $messages")
            }
        }
        for (example in responses) {
            val messages = validator.validateResponse(
                example.method,
                example.path,
                example.status,
                example.contentType,
                example.body,
            )
            if (messages.isNotEmpty()) {
                failures.add("${example.description}: $messages")
            }
        }

        assertEquals(emptyList(), failures)
    }
}
