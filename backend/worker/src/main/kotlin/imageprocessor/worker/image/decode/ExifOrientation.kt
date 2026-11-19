package imageprocessor.worker.image.decode

import com.drew.imaging.ImageProcessingException
import com.drew.imaging.jpeg.JpegMetadataReader
import com.drew.imaging.png.PngChunkReader
import com.drew.imaging.png.PngChunkType
import com.drew.imaging.tiff.TiffReader
import com.drew.lang.ByteArrayReader
import com.drew.lang.SequentialByteArrayReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifDirectoryBase
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifReader
import com.drew.metadata.exif.ExifTiffHandler
import imageprocessor.shared.contract.models.ContentType
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException

/** 1 when the orientation is missing, unreadable or not 1 to 8, as a browser treats it. */
fun exifOrientation(bytes: ByteArray, type: ContentType): Int {
    val metadata = try {
        when (type) {
            ContentType.IMAGE_JPEG -> JpegMetadataReader.readMetadata(ByteArrayInputStream(bytes), listOf(ExifReader()))
            ContentType.IMAGE_PNG -> pngExifMetadata(bytes)
        }
    } catch (_: ImageProcessingException) {
        return 1
    } catch (_: IOException) {
        return 1
    }
    val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java) ?: return 1
    val orientation = ifd0.getInteger(ExifDirectoryBase.TAG_ORIENTATION) ?: return 1
    return if (orientation in 1..8) orientation else 1
}

// PngMetadataReader would inflate every zTXt chunk; asking the chunk reader for eXIf alone skips the others.
private fun pngExifMetadata(png: ByteArray): Metadata {
    val metadata = Metadata()
    // StreamReader would allocate a chunk's declared length before finding the file shorter.
    val chunks = PngChunkReader().extract(SequentialByteArrayReader(png), setOf(PngChunkType.eXIf))
    for (chunk in chunks) {
        TiffReader().processTiff(ByteArrayReader(chunk.bytes), ExifTiffHandler(metadata, null, 0), 0)
    }
    return metadata
}

fun applyOrientation(image: BufferedImage, orientation: Int): BufferedImage {
    if (orientation == 1) {
        return image
    }
    val w = image.width.toDouble()
    val h = image.height.toDouble()
    val transform = AffineTransform()
    when (orientation) {
        2 -> {
            transform.translate(w, 0.0)
            transform.scale(-1.0, 1.0)
        }

        3 -> {
            transform.translate(w, h)
            transform.rotate(Math.PI)
        }

        4 -> {
            transform.translate(0.0, h)
            transform.scale(1.0, -1.0)
        }

        5 -> {
            transform.scale(-1.0, 1.0)
            transform.rotate(Math.PI / 2)
        }

        6 -> {
            transform.translate(h, 0.0)
            transform.rotate(Math.PI / 2)
        }

        7 -> {
            transform.translate(h, w)
            transform.rotate(Math.PI / 2)
            transform.scale(-1.0, 1.0)
        }

        8 -> {
            transform.translate(0.0, w)
            transform.rotate(-Math.PI / 2)
        }
    }
    val swapped = orientation >= 5
    val type = if (image.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
    val target = BufferedImage(
        if (swapped) image.height else image.width,
        if (swapped) image.width else image.height,
        type,
    )
    val graphics = target.createGraphics()
    try {
        graphics.drawImage(image, transform, null)
    } finally {
        graphics.dispose()
    }
    return target
}
