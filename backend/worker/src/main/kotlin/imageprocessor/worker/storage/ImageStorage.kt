package imageprocessor.worker.storage

import imageprocessor.shared.contract.models.ContentType
import java.io.InputStream

sealed interface OriginalRead {
    class Missing(val cause: Exception) : OriginalRead

    // The caller closes the body; the size is known before any byte is read.
    class Found(
        val storedSize: Long,
        val body: InputStream,
    ) : OriginalRead
}

interface ImageStorage {
    fun read(key: String): OriginalRead

    fun write(key: String, bytes: ByteArray, contentType: ContentType)
}
