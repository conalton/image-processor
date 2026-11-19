package imageprocessor.worker.image.fixtures

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO

fun testImage(width: Int = 600, height: Int = 400): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color.RED
    graphics.fillRect(0, 0, width / 2, height / 2)
    graphics.color = Color.GREEN
    graphics.fillRect(width / 2, 0, width - width / 2, height / 2)
    graphics.color = Color.BLUE
    graphics.fillRect(0, height / 2, width / 2, height - height / 2)
    graphics.color = Color.YELLOW
    graphics.fillRect(width / 2, height / 2, width - width / 2, height - height / 2)
    graphics.dispose()
    return image
}

fun encodeAs(image: BufferedImage, format: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    check(ImageIO.write(image, format, bytes))
    return bytes.toByteArray()
}

// One EXIF Orientation tag in an APP1 segment right after SOI.
fun jpegWithOrientation(image: BufferedImage, orientation: Int): ByteArray {
    val jpeg = encodeAs(image, "jpeg")
    val payload = "Exif\u0000\u0000".toByteArray() + orientationTiff(orientation)
    val segment = ByteBuffer.allocate(4 + payload.size)
    segment.put(0xFF.toByte())
    segment.put(0xE1.toByte())
    segment.putShort((payload.size + 2).toShort())
    segment.put(payload)
    return jpeg.copyOfRange(0, 2) + segment.array() + jpeg.copyOfRange(2, jpeg.size)
}

// Only the header declares this size; the pixel data stays 8 x 8.
fun pngDeclaring(width: Int, height: Int): ByteArray {
    val png = encodeAs(testImage(8, 8), "png")
    val widthOffset = 16
    val headerStart = 12
    val headerEnd = 29
    val buffer = ByteBuffer.wrap(png)
    buffer.putInt(widthOffset, width)
    buffer.putInt(widthOffset + 4, height)
    val crc = CRC32()
    crc.update(png, headerStart, headerEnd - headerStart)
    buffer.putInt(headerEnd, crc.value.toInt())
    return png
}

// A PNG with zTXt chunks that each inflate to inflatedBytes; the file itself stays small.
fun pngWithCompressedText(chunks: Int, inflatedBytes: Int): ByteArray {
    val png = encodeAs(testImage(8, 8), "png")
    val afterHeader = 33
    val deflater = Deflater()
    deflater.setInput(ByteArray(inflatedBytes))
    deflater.finish()
    val compressed = ByteArray(inflatedBytes)
    val compressedSize = deflater.deflate(compressed)
    deflater.end()
    val data = "k".toByteArray() + byteArrayOf(0, 0) + compressed.copyOf(compressedSize)
    val chunk = pngChunk("zTXt", data)

    val result = ByteArrayOutputStream()
    result.write(png, 0, afterHeader)
    repeat(chunks) { result.write(chunk) }
    result.write(png, afterHeader, png.size - afterHeader)
    return result.toByteArray()
}

// One EXIF Orientation tag in an eXIf chunk right after IHDR.
fun pngWithOrientation(image: BufferedImage, orientation: Int): ByteArray {
    val png = encodeAs(image, "png")
    val afterHeader = 33
    return png.copyOfRange(0, afterHeader) + pngChunk("eXIf", orientationTiff(orientation)) +
        png.copyOfRange(afterHeader, png.size)
}

// After IDAT, where ImageIO no longer reads, so the image itself still decodes.
fun pngWithTruncatedExif(image: BufferedImage, declaredLength: Int): ByteArray {
    val png = encodeAs(image, "png")
    val trailer = png.size - 12
    val header = ByteBuffer.allocate(8).putInt(declaredLength).put("eXIf".toByteArray()).array()
    return png.copyOfRange(0, trailer) + header + orientationTiff(6) + png.copyOfRange(trailer, png.size)
}

private fun orientationTiff(orientation: Int): ByteArray {
    val tiff = ByteBuffer.allocate(26)
    tiff.put("MM".toByteArray())
    tiff.putShort(42)
    tiff.putInt(8)
    tiff.putShort(1)
    tiff.putShort(0x0112)
    tiff.putShort(3)
    tiff.putInt(1)
    tiff.putShort(orientation.toShort())
    tiff.putShort(0)
    tiff.putInt(0)
    return tiff.array()
}

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val typeBytes = type.toByteArray()
    val crc = CRC32()
    crc.update(typeBytes)
    crc.update(data)
    val chunk = ByteBuffer.allocate(12 + data.size)
    chunk.putInt(data.size)
    chunk.put(typeBytes)
    chunk.put(data)
    chunk.putInt(crc.value.toInt())
    return chunk.array()
}
