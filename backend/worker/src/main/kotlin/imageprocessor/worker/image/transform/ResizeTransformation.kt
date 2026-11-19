package imageprocessor.worker.image.transform

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.worker.image.encode.supportsAlpha
import imageprocessor.worker.image.scale.SourceImage
import java.awt.image.BufferedImage

class ResizeTransformation(
    override val name: String,
    override val label: String,
    private val maxSide: Int,
    override val format: ContentType,
) : ImageTransformation {
    override fun transform(source: SourceImage): BufferedImage =
        source.fitWithin(maxSide, keepAlpha = format.supportsAlpha)
}
