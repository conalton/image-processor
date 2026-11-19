package imageprocessor.worker.storage

import imageprocessor.shared.contract.models.ContentType
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest

class S3ImageStorage(
    private val client: S3Client,
    private val bucket: String,
) : ImageStorage {
    override fun read(key: String): OriginalRead {
        val request = GetObjectRequest.builder().bucket(bucket).key(key).build()
        try {
            val response = client.getObject(request)
            return OriginalRead.Found(response.response().contentLength(), response)
        } catch (e: NoSuchKeyException) {
            return OriginalRead.Missing(e)
        }
    }

    override fun write(key: String, bytes: ByteArray, contentType: ContentType) {
        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType.value)
            .build()
        client.putObject(request, RequestBody.fromBytes(bytes))
    }
}
