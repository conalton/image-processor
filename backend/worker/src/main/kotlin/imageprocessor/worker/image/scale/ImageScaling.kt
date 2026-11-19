package imageprocessor.worker.image.scale

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

internal fun resized(image: BufferedImage, width: Int, height: Int, type: Int, interpolation: Any): BufferedImage {
    val target = BufferedImage(width, height, type)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        if (type == BufferedImage.TYPE_INT_RGB) {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
        }
        graphics.drawImage(image, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    return target
}
