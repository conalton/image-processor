package imageprocessor.api.signing

import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.utils.http.SdkHttpUtils

private val UNSAFE_CHARACTERS = Regex("[^A-Za-z0-9._-]")

class LinkSigner(
    private val presigner: S3Presigner,
    private val bucket: String,
) {

    fun viewUrl(key: String): String = sign(GetObjectRequest.builder().bucket(bucket).key(key))

    fun downloadUrl(key: String, fileName: String): String =
        sign(
            GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .responseContentDisposition(contentDisposition(fileName)),
        )

    // RFC 6266: an ASCII fallback that cannot end the quoted string, and the full name in RFC 8187 encoding.
    // SdkHttpUtils.urlEncode keeps only RFC 3986 unreserved characters, a subset of attr-char.
    private fun contentDisposition(fileName: String): String {
        val fallback = fileName.replace(UNSAFE_CHARACTERS, "_")
        return "attachment; filename=\"$fallback\"; filename*=UTF-8''${SdkHttpUtils.urlEncode(fileName)}"
    }

    private fun sign(request: GetObjectRequest.Builder): String {
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(LINK_LIFETIME)
            .getObjectRequest(request.build())
            .build()
        return presigner.presignGetObject(presignRequest).url().toString()
    }
}
