package imageprocessor.api.signing

import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest

// Fixed and signed: an unsigned header is the uploader's to set, and the view link serves what is stored.
private const val CONTENT_DISPOSITION = "inline"
private const val CACHE_CONTROL = "private"

data class SignedUpload(
    val url: String,
    val headers: Map<String, String>,
)

class UploadSigner(
    private val presigner: S3Presigner,
    private val bucket: String,
) {

    fun sign(key: String, contentType: String, contentLength: Long): SignedUpload {
        val putRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength)
            .contentDisposition(CONTENT_DISPOSITION)
            .cacheControl(CACHE_CONTROL)
            .ifNoneMatch("*")
            .build()
        val presignRequest = PutObjectPresignRequest.builder()
            .signatureDuration(UPLOAD_URL_LIFETIME)
            .putObjectRequest(putRequest)
            .build()
        val presigned = presigner.presignPutObject(presignRequest)

        // The client sets Host itself; every other signed header has to be sent with the request.
        val headers = presigned.signedHeaders()
            .filterKeys { name -> !name.equals("host", ignoreCase = true) }
            .mapValues { entry -> entry.value.joinToString(",") }
        return SignedUpload(url = presigned.url().toString(), headers = headers)
    }
}
