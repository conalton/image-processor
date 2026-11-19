package imageprocessor.shared.files

import imageprocessor.shared.contract.models.ContentType

val ContentType.fileExtension: String
    get() = when (this) {
        ContentType.IMAGE_JPEG -> "jpg"
        ContentType.IMAGE_PNG -> "png"
    }
