package imageprocessor.api.tasks

import imageprocessor.api.contract.SpecConstraints
import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.contract.models.CreateTaskRequest
import imageprocessor.shared.contract.models.ErrorCode
import imageprocessor.shared.files.MAX_UPLOAD_BYTES

sealed interface CreateTaskValidation {
    data class Valid(
        val fileName: String,
        val contentType: ContentType,
        val size: Long,
    ) : CreateTaskValidation

    data class Rejected(
        val code: ErrorCode,
        val message: String,
    ) : CreateTaskValidation
}

// The spec checks the name and the minimum size; the content type and the upload limit have their own error codes.
fun validateCreateTask(request: CreateTaskRequest, constraints: SpecConstraints): CreateTaskValidation {
    val violations = constraints.violations(request)
    if (violations.isNotEmpty()) {
        return CreateTaskValidation.Rejected(
            ErrorCode.INVALID_REQUEST,
            "The request does not match the spec: ${violations.joinToString("; ")}.",
        )
    }
    val contentType = ContentType.fromValue(request.contentType.lowercase())
        ?: return CreateTaskValidation.Rejected(
            ErrorCode.UNSUPPORTED_CONTENT_TYPE,
            "Only ${acceptedContentTypes()} are accepted.",
        )
    if (request.size > MAX_UPLOAD_BYTES) {
        return CreateTaskValidation.Rejected(
            ErrorCode.FILE_TOO_LARGE,
            "The file is larger than the allowed size.",
        )
    }
    return CreateTaskValidation.Valid(request.fileName, contentType, request.size)
}

private fun acceptedContentTypes() = ContentType.entries.joinToString(" and ") { it.value }
