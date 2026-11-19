package imageprocessor.worker

import imageprocessor.shared.files.taskIdOfOriginalKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val json = Json { ignoreUnknownKeys = true }

private const val TEST_EVENT = "s3:TestEvent"

// Only S3 may send to the queue: another object throws like invalid JSON, so that it reaches the dead-letter queue.
fun decodeS3Notification(body: String): S3Notification {
    val notification = json.decodeFromString(S3Notification.serializer(), body)
    require(notification.records != null || notification.event == TEST_EVENT) { "Not an S3 notification" }
    return notification
}

@Serializable
data class S3Notification(
    @SerialName("Records") val records: List<S3Record>? = null,
    @SerialName("Event") val event: String? = null,
) {
    fun taskIds(originalsPrefix: String): List<String> =
        records.orEmpty().mapNotNull { record ->
            // S3 encodes the key like a form field: a space arrives as '+'.
            val key = URLDecoder.decode(record.s3.storedObject.key, StandardCharsets.UTF_8)
            taskIdOfOriginalKey(originalsPrefix, key)
        }
}

@Serializable
data class S3Record(val s3: S3Entity)

@Serializable
data class S3Entity(@SerialName("object") val storedObject: S3Object)

@Serializable
data class S3Object(val key: String)
