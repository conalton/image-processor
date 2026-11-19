package imageprocessor.api.http

import imageprocessor.shared.contract.models.ApiError
import imageprocessor.shared.contract.models.ErrorCode
import imageprocessor.shared.files.MAX_UPLOAD_BYTES
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

data class ApiRequest(
    val method: String,
    val path: String,
    val body: String?,
)

data class ApiResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

internal const val JSON_MEDIA_TYPE = "application/json"

fun <T> jsonResponse(status: Int, serializer: KSerializer<T>, value: T): ApiResponse =
    ApiResponse(status = status, body = Json.encodeToString(serializer, value))

fun errorResponse(code: ErrorCode, message: String): ApiResponse {
    val status = when (code) {
        ErrorCode.INVALID_REQUEST, ErrorCode.UNSUPPORTED_CONTENT_TYPE, ErrorCode.FILE_TOO_LARGE -> 400
        ErrorCode.TASK_NOT_FOUND, ErrorCode.NOT_FOUND -> 404
        ErrorCode.METHOD_NOT_ALLOWED -> 405
        ErrorCode.INTERNAL_ERROR -> 500
    }
    val maxUploadBytes = if (code == ErrorCode.FILE_TOO_LARGE) MAX_UPLOAD_BYTES else null
    return jsonResponse(status, ApiError.serializer(), ApiError(code, message, maxUploadBytes))
}
