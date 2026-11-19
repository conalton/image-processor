package imageprocessor.worker.image.decode

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.contract.models.InputRejectionCode
import java.io.ByteArrayInputStream
import javax.imageio.IIOException
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadWarningListener
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream

private const val MAX_KEPT_WARNINGS = 5

fun decodeImage(bytes: ByteArray, signedType: ContentType): DecodeOutcome {
    try {
        val outcome = MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            decodeFrom(input, signedType)
        }
        if (outcome is DecodeOutcome.Decoded) {
            return DecodeOutcome.Decoded(applyOrientation(outcome.image, exifOrientation(bytes, signedType)))
        }
        return outcome
    } catch (error: IIOException) {
        // A read error from memory is a broken file or a JPEG mode the JDK lacks (arithmetic-coded, lossless).
        return DecodeOutcome.Rejected(InputRejectionCode.UNDECODABLE_IMAGE, "reader error: ${error.message}")
    } catch (error: Exception) {
        return DecodeOutcome.Failed(error)
    }
}

private fun decodeFrom(input: ImageInputStream, signedType: ContentType): DecodeOutcome {
    val readers = ImageIO.getImageReaders(input)
    if (!readers.hasNext()) {
        return DecodeOutcome.Rejected(InputRejectionCode.UNDECODABLE_IMAGE)
    }
    val reader = readers.next()
    try {
        // ignoreMetadata: otherwise the PNG reader inflates and keeps every compressed text chunk.
        reader.setInput(input, false, true)
        if (detectedType(reader) != signedType) {
            return DecodeOutcome.Rejected(InputRejectionCode.CONTENT_TYPE_MISMATCH)
        }
        val pixels = reader.getWidth(0).toLong() * reader.getHeight(0).toLong()
        if (pixels > MAX_PIXELS) {
            return DecodeOutcome.Rejected(InputRejectionCode.PIXEL_LIMIT_EXCEEDED)
        }
        val warnings = ReadWarnings()
        reader.addIIOReadWarningListener(warnings)
        val image = reader.read(0)
        // The JPEG reader reports a truncated file only as a warning and returns the part it could read.
        if (warnings.messages.isNotEmpty()) {
            return DecodeOutcome.Rejected(
                InputRejectionCode.UNDECODABLE_IMAGE,
                "reader warnings: ${warnings.messages.joinToString("; ")}",
            )
        }
        return DecodeOutcome.Decoded(image)
    } finally {
        reader.dispose()
    }
}

private fun detectedType(reader: ImageReader): ContentType? {
    val mimeTypes = reader.originatingProvider.mimeTypes ?: return null
    return ContentType.entries.firstOrNull { mimeTypes.contains(it.value) }
}

private class ReadWarnings : IIOReadWarningListener {
    val messages = mutableListOf<String>()

    override fun warningOccurred(source: ImageReader, warning: String) {
        if (messages.size < MAX_KEPT_WARNINGS) {
            messages.add(warning)
        }
    }
}
