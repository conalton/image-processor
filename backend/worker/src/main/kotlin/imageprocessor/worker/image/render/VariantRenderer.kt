package imageprocessor.worker.image.render

import imageprocessor.worker.image.encode.encodeImage
import imageprocessor.worker.image.scale.SourceImage
import imageprocessor.worker.image.transform.ImageTransformation
import java.awt.image.BufferedImage

fun renderVariants(image: BufferedImage, transformations: List<ImageTransformation>): List<EncodedVariant> {
    val source = SourceImage(image)
    return transformations.map { transformation ->
        val result = transformation.transform(source)
        EncodedVariant(
            name = transformation.name,
            label = transformation.label,
            contentType = transformation.format,
            width = result.width,
            height = result.height,
            bytes = encodeImage(result, transformation.format),
        )
    }
}
