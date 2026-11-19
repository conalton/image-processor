package imageprocessor.worker.image.encode

import imageprocessor.shared.contract.models.ContentType
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream

fun encodeImage(image: BufferedImage, format: ContentType): ByteArray {
    val writers = ImageIO.getImageWritersByMIMEType(format.value)
    check(writers.hasNext()) { "No ImageIO writer for ${format.value}" }
    val writer = writers.next()
    val bytes = ByteArrayOutputStream()
    try {
        MemoryCacheImageOutputStream(bytes).use { output ->
            writer.output = output
            writer.write(image)
        }
    } finally {
        writer.dispose()
    }
    return bytes.toByteArray()
}

val ContentType.supportsAlpha: Boolean
    get() = this == ContentType.IMAGE_PNG
