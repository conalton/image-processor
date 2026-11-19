package imageprocessor.api.tasks

import imageprocessor.api.contract.SpecConstraints
import imageprocessor.api.http.ApiResponse
import imageprocessor.api.http.errorResponse
import imageprocessor.api.http.jsonResponse
import imageprocessor.api.signing.UploadSigner
import imageprocessor.shared.contract.models.CreateTaskRequest
import imageprocessor.shared.contract.models.CreateTaskResponse
import imageprocessor.shared.contract.models.ErrorCode
import imageprocessor.shared.contract.models.UploadInstructions
import imageprocessor.shared.contract.models.UploadInstructionsMethod
import imageprocessor.shared.files.originalKey
import imageprocessor.shared.store.TaskRepository
import imageprocessor.shared.tasks.NewTask
import imageprocessor.shared.tasks.WriteOutcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.util.UUID

private val requestJson = Json { ignoreUnknownKeys = true }

/** The request is three short fields; the limit keeps an oversized body away from the JSON parser. */
internal const val MAX_REQUEST_BODY_BYTES = 16 * 1024

class TaskCreator(
    private val tasks: TaskRepository,
    private val signer: UploadSigner,
    private val originalsPrefix: String,
    private val retention: Duration,
    private val constraints: SpecConstraints,
) {
    fun create(body: String?): ApiResponse {
        val text = body.orEmpty()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BODY_BYTES) {
            return errorResponse(ErrorCode.INVALID_REQUEST, "The request body is too large.")
        }
        val request = readRequest(text)
            ?: return errorResponse(ErrorCode.INVALID_REQUEST, "The request body is not valid.")
        val validation = validateCreateTask(request, constraints)
        return when (validation) {
            is CreateTaskValidation.Rejected -> errorResponse(validation.code, validation.message)
            is CreateTaskValidation.Valid -> createTask(validation)
        }
    }

    private fun createTask(valid: CreateTaskValidation.Valid): ApiResponse {
        val id = UUID.randomUUID().toString()
        val key = originalKey(originalsPrefix, id)
        val outcome = tasks.create(
            NewTask(
                id = id,
                fileName = valid.fileName,
                contentType = valid.contentType,
                size = valid.size,
                originalKey = key,
                retention = retention,
            ),
        )
        check(outcome == WriteOutcome.Written) { "Task $id already exists" }

        val upload = signer.sign(key = key, contentType = valid.contentType.value, contentLength = valid.size)
        val instructions = UploadInstructions(
            method = UploadInstructionsMethod.PUT,
            url = upload.url,
            headers = upload.headers,
        )
        return jsonResponse(201, CreateTaskResponse.serializer(), CreateTaskResponse(id, instructions))
    }
}

private fun readRequest(text: String): CreateTaskRequest? {
    try {
        val body = requestJson.parseToJsonElement(text) as? JsonObject ?: return null
        val size = body["size"] as? JsonPrimitive ?: return null
        // kotlinx.serialization wraps a number above the Long range (2^64 + 5 reads as 5).
        if (size.content.toLongOrNull() == null) return null
        return requestJson.decodeFromJsonElement(CreateTaskRequest.serializer(), body)
    } catch (_: SerializationException) {
        return null
    }
}
