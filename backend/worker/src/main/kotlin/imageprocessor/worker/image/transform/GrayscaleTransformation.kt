package imageprocessor.worker.image.transform

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.worker.image.color.toGray
import imageprocessor.worker.image.scale.SourceImage
import java.awt.image.BufferedImage

class GrayscaleTransformation(
    override val name: String,
    override val label: String,
    private val maxSide: Int,
    override val format: ContentType,
) : ImageTransformation {
    override fun transform(source: SourceImage): BufferedImage = toGray(source.fitWithin(maxSide, keepAlpha = false))
}
