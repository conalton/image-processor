package imageprocessor.shared.tasks

import imageprocessor.shared.contract.models.ContentType
import java.time.Duration
import java.time.Instant

data class StoredVariant(
    val name: String,
    val label: String,
    val key: String,
    val contentType: ContentType,
    val width: Int,
    val height: Int,
    val size: Long,
)

data class TaskItem(
    val id: String,
    val state: TaskState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
    val fileName: String,
    val contentType: ContentType,
    val size: Long,
    val originalKey: String,
) {
    // DynamoDB deletes expired items lazily: a read can still return one past its TTL.
    fun isExpired(now: Instant): Boolean = now.isAfter(expiresAt)
}

data class NewTask(
    val id: String,
    val fileName: String,
    val contentType: ContentType,
    val size: Long,
    val originalKey: String,
    val retention: Duration,
) {
    fun toItem(now: Instant): TaskItem =
        TaskItem(
            id = id,
            state = TaskState.PendingUpload,
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plus(retention),
            fileName = fileName,
            contentType = contentType,
            size = size,
            originalKey = originalKey,
        )
}
