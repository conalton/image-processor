package imageprocessor.worker.image.transform

import imageprocessor.shared.contract.models.ContentType

val VARIANT_CATALOGUE: List<ImageTransformation> =
    listOf(
        ResizeTransformation("medium", "Medium", maxSide = 1024, format = ContentType.IMAGE_JPEG),
        ResizeTransformation("thumbnail", "Thumbnail", maxSide = 256, format = ContentType.IMAGE_JPEG),
        GrayscaleTransformation("grayscale", "Grayscale", maxSide = 1024, format = ContentType.IMAGE_JPEG),
        ResizeTransformation("png", "PNG", maxSide = 1024, format = ContentType.IMAGE_PNG),
    )
