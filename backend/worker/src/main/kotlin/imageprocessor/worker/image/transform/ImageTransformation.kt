package imageprocessor.worker.image.transform

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.worker.image.scale.SourceImage
import java.awt.image.BufferedImage

interface ImageTransformation {
    val name: String
    val label: String
    val format: ContentType

    fun transform(source: SourceImage): BufferedImage
}
