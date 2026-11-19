package imageprocessor.api.contract

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.atlassian.oai.validator.schema.SchemaValidator

class ContractValidator(specPath: String) {
    // The validator refuses properties that a schema does not list, which the spec allows.
    private val validator = OpenApiInteractionValidator.createFor(specPath)
        .withLevelResolver(
            LevelResolver.create()
                .withLevel(SchemaValidator.ADDITIONAL_PROPERTIES_KEY, ValidationReport.Level.IGNORE)
                .build(),
        )
        .build()

    fun validateRequest(method: String, path: String, contentType: String, body: String): List<String> {
        val request = SimpleRequest.Builder(Request.Method.valueOf(method), path)
            .withContentType(contentType)
            .withBody(body)
            .build()
        return messages(validator.validateRequest(request))
    }

    fun validateResponse(method: String, path: String, status: Int, contentType: String, body: String): List<String> {
        val response = SimpleResponse.Builder.status(status)
            .withContentType(contentType)
            .withBody(body)
            .build()
        return messages(validator.validateResponse(path, Request.Method.valueOf(method), response))
    }

    private fun messages(report: ValidationReport): List<String> =
        report.messages.map { message ->
            "${message.key}: ${message.message}"
        }
}
