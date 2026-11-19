package imageprocessor.api.contract

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions
import io.swagger.v3.core.util.Json as SwaggerJson

data class RequestExample(
    val description: String,
    val method: String,
    val path: String,
    val contentType: String,
    val body: String,
)

data class ResponseExample(
    val description: String,
    val method: String,
    val path: String,
    val status: Int,
    val contentType: String,
    val body: String,
)

data class SpecOperation(
    val method: String,
    val pathTemplate: String,
    val path: String,
    val statuses: Set<Int>,
)

class SpecReader(specPath: String) {
    private val openApi: OpenAPI = readSpec(specPath)

    fun operations(): List<SpecOperation> {
        val operations = mutableListOf<SpecOperation>()
        for (pathEntry in openApi.paths.entries) {
            for (operationEntry in pathEntry.value.readOperationsMap().entries) {
                operations.add(
                    SpecOperation(
                        method = operationEntry.key.name,
                        pathTemplate = pathEntry.key,
                        path = concretePath(pathEntry.key, operationEntry.value),
                        statuses = operationEntry.value.responses.keys.map { status -> status.toInt() }.toSet(),
                    ),
                )
            }
        }
        return operations
    }

    fun property(schemaName: String, propertyName: String): Schema<*> {
        val schema = requireNotNull(openApi.components.schemas[schemaName]) { "No schema $schemaName in the spec" }
        return requireNotNull(schema.properties[propertyName]) { "No property $propertyName in $schemaName" }
    }

    fun requestExamples(): List<RequestExample> {
        val examples = mutableListOf<RequestExample>()
        for (pathEntry in openApi.paths.entries) {
            for (operationEntry in pathEntry.value.readOperationsMap().entries) {
                val method = operationEntry.key.name
                val path = concretePath(pathEntry.key, operationEntry.value)
                val content = operationEntry.value.requestBody?.content ?: continue
                for (mediaEntry in content.entries) {
                    for (bodyEntry in bodies(mediaEntry.value).entries) {
                        examples.add(
                            RequestExample(
                                "$method $path ${bodyEntry.key}",
                                method,
                                path,
                                mediaEntry.key,
                                bodyEntry.value,
                            ),
                        )
                    }
                }
            }
        }
        return examples
    }

    fun responseExamples(): List<ResponseExample> {
        val examples = mutableListOf<ResponseExample>()
        for (pathEntry in openApi.paths.entries) {
            for (operationEntry in pathEntry.value.readOperationsMap().entries) {
                val method = operationEntry.key.name
                val path = concretePath(pathEntry.key, operationEntry.value)
                for (responseEntry in operationEntry.value.responses.entries) {
                    val content = responseEntry.value.content ?: continue
                    for (mediaEntry in content.entries) {
                        for (bodyEntry in bodies(mediaEntry.value).entries) {
                            examples.add(
                                ResponseExample(
                                    description = "$method $path ${responseEntry.key} ${bodyEntry.key}",
                                    method = method,
                                    path = path,
                                    status = responseEntry.key.toInt(),
                                    contentType = mediaEntry.key,
                                    body = bodyEntry.value,
                                ),
                            )
                        }
                    }
                }
            }
        }
        return examples
    }

    fun taskStatuses(): Set<String> {
        val task = requireNotNull(openApi.components.schemas["Task"]) { "No schema Task in the spec" }
        return task.oneOf.map { branch ->
            val status = requireNotNull(branch.properties["status"]) { "A branch of Task has no status" }
            status.const.toString()
        }.toSet()
    }

    private fun bodies(mediaType: MediaType): Map<String, String> {
        val bodies = linkedMapOf<String, String>()
        if (mediaType.example != null) {
            bodies["example"] = SwaggerJson.mapper().writeValueAsString(mediaType.example)
        }
        val named = mediaType.examples
        if (named != null) {
            for (entry in named.entries) {
                bodies[entry.key] = SwaggerJson.mapper().writeValueAsString(entry.value.value)
            }
        }
        return bodies
    }

    private fun concretePath(template: String, operation: Operation): String {
        var path = template
        for (parameter in operation.parameters.orEmpty()) {
            if (parameter.`in` == "path") {
                val example = requireNotNull(parameter.example) {
                    "Path parameter ${parameter.name} needs an example in the spec"
                }
                path = path.replace("{${parameter.name}}", example.toString())
            }
        }
        return path
    }
}

private fun readSpec(specPath: String): OpenAPI {
    val options = ParseOptions()
    options.isResolveFully = true
    val result = OpenAPIV3Parser().readLocation(specPath, null, options)
    check(result.messages.isEmpty()) { "The spec has parse messages: ${result.messages}" }
    return requireNotNull(result.openAPI) { "The spec could not be read" }
}
