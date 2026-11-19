package imageprocessor.api

import imageprocessor.api.http.ApiRequest
import imageprocessor.api.http.ApiResponse
import imageprocessor.api.http.errorResponse
import imageprocessor.api.tasks.TaskCreator
import imageprocessor.api.tasks.TaskReader
import imageprocessor.shared.contract.models.ErrorCode

private val TASK_PATH = Regex("^/api/tasks/([^/]+)$")

class ApiRouter(
    private val taskCreator: TaskCreator,
    private val taskReader: TaskReader,
) {
    fun route(request: ApiRequest): ApiResponse {
        val path = request.path
        val taskPath = TASK_PATH.matchEntire(path)
        return when {
            path == "/api/tasks" -> allow("POST", request) { taskCreator.create(request.body) }
            taskPath != null -> allow("GET", request) { taskReader.read(taskPath.groupValues[1]) }
            else -> errorResponse(ErrorCode.NOT_FOUND, "The resource does not exist.")
        }
    }

    private fun allow(method: String, request: ApiRequest, call: () -> ApiResponse): ApiResponse =
        if (request.method == method) {
            call()
        } else {
            val response = errorResponse(ErrorCode.METHOD_NOT_ALLOWED, "Use $method for ${request.path}.")
            response.copy(headers = mapOf("Allow" to method))
        }
}
