package imageprocessor.worker.image.decode

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.contract.models.InputRejectionCode
import imageprocessor.worker.image.fixtures.encodeAs
import imageprocessor.worker.image.fixtures.jpegWithOrientation
import imageprocessor.worker.image.fixtures.pngDeclaring
import imageprocessor.worker.image.fixtures.pngWithCompressedText
import imageprocessor.worker.image.fixtures.pngWithOrientation
import imageprocessor.worker.image.fixtures.pngWithTruncatedExif
import imageprocessor.worker.image.fixtures.testImage
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImageDecoderTest {
    @Test
    fun `rejects random bytes as undecodable`() {
        val bytes = ByteArray(256)
        Random(1).nextBytes(bytes)

        assertRejected(InputRejectionCode.UNDECODABLE_IMAGE, decodeImage(bytes, ContentType.IMAGE_PNG))
    }

    @Test
    fun `rejects a JPEG signed as PNG`() {
        val jpeg = encodeAs(testImage(), "jpeg")

        assertRejected(InputRejectionCode.CONTENT_TYPE_MISMATCH, decodeImage(jpeg, ContentType.IMAGE_PNG))
    }

    @Test
    fun `accepts exactly the pixel limit and rejects one pixel more`() {
        // 4000 x 4000 is MAX_PIXELS; the others are read from the header only, and 65536 x 65536 overflows Int to 0
        assertIs<DecodeOutcome.Decoded>(decodeImage(encodeAs(testImage(4000, 4000), "png"), ContentType.IMAGE_PNG))
        for ((width, height) in listOf(16_000_001 to 1, 65_536 to 65_536)) {
            assertRejected(
                InputRejectionCode.PIXEL_LIMIT_EXCEEDED,
                decodeImage(pngDeclaring(width, height), ContentType.IMAGE_PNG),
            )
        }
    }

    @Test
    fun `rejects a truncated JPEG or PNG as undecodable`() {
        // The PNG reader throws; the JPEG reader only warns and returns the part it has.
        val jpeg = encodeAs(testImage(), "jpeg")
        val png = encodeAs(testImage(), "png")

        assertRejected(
            InputRejectionCode.UNDECODABLE_IMAGE,
            decodeImage(jpeg.copyOf(jpeg.size / 2), ContentType.IMAGE_JPEG),
        )
        assertRejected(
            InputRejectionCode.UNDECODABLE_IMAGE,
            decodeImage(png.copyOf(png.size / 2), ContentType.IMAGE_PNG),
        )
    }

    @Test
    fun `decodes a PNG with compressed text chunks without keeping them`() {
        // About 6 MB of file; kept metadata would overflow the test heap (maxHeapSize in build.gradle.kts).
        val png = pngWithCompressedText(chunks = 6000, inflatedBytes = 1 shl 20)

        assertIs<DecodeOutcome.Decoded>(decodeImage(png, ContentType.IMAGE_PNG))
    }

    @Test
    fun `applies the EXIF orientations 1 to 8 and treats any other as upright`() {
        val expected = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
        )

        for (orientation in 1..9) {
            val jpeg = jpegWithOrientation(testImage(60, 40), orientation)

            val decoded = assertIs<DecodeOutcome.Decoded>(decodeImage(jpeg, ContentType.IMAGE_JPEG))

            val swapped = orientation in 5..8
            assertEquals(if (swapped) 40 else 60, decoded.image.width, "width for orientation $orientation")
            assertEquals(if (swapped) 60 else 40, decoded.image.height, "height for orientation $orientation")
            assertQuadrants(decoded.image, expected[orientation - 1])
        }
    }

    @Test
    fun `rotates a PNG by its eXIf orientation and keeps its alpha`() {
        val source = BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB)
        source.createGraphics().also { graphics ->
            graphics.drawImage(testImage(60, 40), 0, 0, null)
            graphics.composite = AlphaComposite.Clear
            graphics.fillRect(0, 0, 30, 20)
            graphics.dispose()
        }

        val decoded = assertIs<DecodeOutcome.Decoded>(
            decodeImage(pngWithOrientation(source, 6), ContentType.IMAGE_PNG),
        )

        assertEquals(40, decoded.image.width)
        assertEquals(60, decoded.image.height)
        assertEquals(0, decoded.image.getRGB(30, 15) ushr 24, "the transparent quadrant moved to the top right")
        assertEquals(Color.BLUE.rgb, decoded.image.getRGB(10, 15), "the opaque top left is the old bottom left")
    }

    @Test
    fun `treats an eXIf chunk that declares more bytes than the file holds as upright`() {
        for (declaredLength in listOf(300_000_000, Int.MAX_VALUE)) {
            val png = pngWithTruncatedExif(testImage(60, 40), declaredLength)

            val decoded = assertIs<DecodeOutcome.Decoded>(decodeImage(png, ContentType.IMAGE_PNG))

            assertEquals(60, decoded.image.width, "width for declared length $declaredLength")
            assertQuadrants(decoded.image, listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW))
        }
    }

    // Order: top-left, top-right, bottom-left, bottom-right; the tolerance absorbs JPEG loss.
    private fun assertQuadrants(image: BufferedImage, expected: List<Color>) {
        val centres = listOf(
            Pair(image.width / 4, image.height / 4),
            Pair(image.width * 3 / 4, image.height / 4),
            Pair(image.width / 4, image.height * 3 / 4),
            Pair(image.width * 3 / 4, image.height * 3 / 4),
        )
        for (index in 0..3) {
            val actual = Color(image.getRGB(centres[index].first, centres[index].second))
            val wanted = expected[index]
            val message = "quadrant $index: expected $wanted, was $actual"
            assertTrue(abs(actual.red - wanted.red) <= 40, message)
            assertTrue(abs(actual.green - wanted.green) <= 40, message)
            assertTrue(abs(actual.blue - wanted.blue) <= 40, message)
        }
    }

    private fun assertRejected(expected: InputRejectionCode, outcome: DecodeOutcome) {
        val rejected = assertIs<DecodeOutcome.Rejected>(outcome)
        assertEquals(expected, rejected.code)
    }
}
