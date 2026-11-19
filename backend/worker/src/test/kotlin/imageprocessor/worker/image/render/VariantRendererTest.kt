package imageprocessor.worker.image.render

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.worker.image.fixtures.testImage
import imageprocessor.worker.image.transform.GrayscaleTransformation
import imageprocessor.worker.image.transform.ImageTransformation
import imageprocessor.worker.image.transform.ResizeTransformation
import imageprocessor.worker.image.transform.VARIANT_CATALOGUE
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VariantRendererTest {
    private val thumbnail = ResizeTransformation("thumbnail", "Thumbnail", maxSide = 256, ContentType.IMAGE_JPEG)
    private val gray = GrayscaleTransformation("gray", "Gray", maxSide = 256, ContentType.IMAGE_JPEG)
    private val png = ResizeTransformation("png", "PNG", maxSide = 256, ContentType.IMAGE_PNG)
    private val grayPng = GrayscaleTransformation("gray-png", "Gray PNG", maxSide = 256, ContentType.IMAGE_PNG)

    @Test
    fun `scales the longer side to maxSide, never enlarges and keeps one pixel on the short side`() {
        val sizes = listOf(
            Triple(600, 400, 256 to 171),
            Triple(400, 600, 171 to 256),
            Triple(4000, 1, 256 to 1),
            Triple(100, 50, 100 to 50),
        )

        for ((width, height, expected) in sizes) {
            val variant = render(testImage(width, height), thumbnail)

            assertEquals(expected, variant.width to variant.height, "${width}x$height")
            val written = read(variant.bytes)
            assertEquals(variant.width, written.width)
            assertEquals(variant.height, written.height)
        }
    }

    @Test
    fun `encodes to the format of the variant`() {
        assertEquals("JPEG", formatOf(render(testImage(), thumbnail).bytes))
        assertEquals("png", formatOf(render(testImage(), png).bytes))
        assertEquals(ContentType.IMAGE_PNG, render(testImage(), png).contentType)
    }

    @Test
    fun `grayscale has equal channels and keeps the tone`() {
        val written = read(render(testImage(), gray).bytes)

        for (x in 0 until written.width step 7) {
            for (y in 0 until written.height step 7) {
                val rgb = written.getRGB(x, y)
                assertEquals(channel(rgb, shift = 16), channel(rgb, shift = 8))
                assertEquals(channel(rgb, shift = 8), channel(rgb, shift = 0))
            }
        }

        for (tone in listOf(64, 128, 192)) {
            val source = uniformImage(tone)

            assertEquals(tone.toDouble(), sample(render(source, grayPng).bytes), 2.0)
            assertEquals(tone.toDouble(), sample(render(source, gray).bytes), 2.0)
        }
    }

    @Test
    fun `a fine pattern shrinks to its average tone instead of aliasing`() {
        val source = BufferedImage(2000, 2000, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until source.width) {
            for (y in 0 until source.height) {
                source.setRGB(x, y, if ((x / 3 + y / 3) % 2 == 0) 0x000000 else 0xffffff)
            }
        }

        val written = read(render(source, grayPng).bytes)

        for (x in 8 until written.width - 8 step 5) {
            for (y in 8 until written.height - 8 step 5) {
                assertEquals(128.0, written.raster.getSampleDouble(x, y, 0), 40.0, "pixel $x,$y")
            }
        }
    }

    @Test
    fun `flattens transparency onto white for JPEG and grayscale and keeps it in PNG`() {
        val transparent = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)

        val flattened = read(render(transparent, thumbnail).bytes).getRGB(20, 20)
        assertTrue(channel(flattened, shift = 16) > 240)
        assertTrue(channel(flattened, shift = 8) > 240)
        assertTrue(channel(flattened, shift = 0) > 240)

        assertTrue(sample(render(transparent, grayPng).bytes) > 240)

        assertEquals(0, channel(read(render(transparent, png).bytes).getRGB(20, 20), shift = 24))
    }

    @Test
    fun `the catalogue rendered together is byte-identical to each version rendered alone`() {
        for (type in listOf(BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_INT_ARGB)) {
            // Noise with random alpha: semi-transparent pixels show any change in where transparency is flattened.
            val source = BufferedImage(4098, 403, type)
            val random = Random(1)
            for (x in 0 until source.width) {
                for (y in 0 until source.height) {
                    source.setRGB(x, y, random.nextInt())
                }
            }

            val together = renderVariants(source, VARIANT_CATALOGUE)

            for ((transformation, variant) in VARIANT_CATALOGUE.zip(together)) {
                assertContentEquals(
                    render(source, transformation).bytes,
                    variant.bytes,
                    "${transformation.name}, type $type",
                )
            }
        }
    }

    private fun render(image: BufferedImage, transformation: ImageTransformation): EncodedVariant =
        renderVariants(image, listOf(transformation)).single()

    private fun uniformImage(tone: Int): BufferedImage {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                image.setRGB(x, y, (tone shl 16) or (tone shl 8) or tone)
            }
        }
        return image
    }

    private fun sample(bytes: ByteArray): Double = read(bytes).raster.getSampleDouble(16, 16, 0)

    private fun channel(rgb: Int, shift: Int): Int = (rgb shr shift) and 0xff

    private fun read(bytes: ByteArray): BufferedImage = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))

    private fun formatOf(bytes: ByteArray): String =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            ImageIO.getImageReaders(input).next().formatName
        }
}
