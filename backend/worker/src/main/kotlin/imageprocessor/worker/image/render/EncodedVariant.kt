package imageprocessor.worker.image.render

import imageprocessor.shared.contract.models.ContentType

class EncodedVariant(
    val name: String,
    val label: String,
    val contentType: ContentType,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
) {
    val size: Long = bytes.size.toLong()
}
