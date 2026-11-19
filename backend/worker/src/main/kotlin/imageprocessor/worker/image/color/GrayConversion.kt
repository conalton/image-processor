package imageprocessor.worker.image.color

import java.awt.image.BufferedImage

fun toGray(image: BufferedImage): BufferedImage {
    val target = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
    val graphics = target.createGraphics()
    try {
        graphics.drawImage(image, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return target
}
