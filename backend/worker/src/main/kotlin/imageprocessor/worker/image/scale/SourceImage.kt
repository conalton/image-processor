package imageprocessor.worker.image.scale

import java.awt.RenderingHints
import java.awt.image.BufferedImage

class SourceImage(private val original: BufferedImage) {
    private val hasAlpha = original.colorModel.hasAlpha()
    private val opaqueHalvings = mutableListOf(original)
    private val alphaHalvings = mutableListOf(original)

    fun fitWithin(maxSide: Int, keepAlpha: Boolean): BufferedImage {
        val type = if (keepAlpha && hasAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val (width, height) = targetSize(maxSide)
        return resized(reduced(width, height, type), width, height, type, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    }

    // Sizes come from the original image: sizes computed from a halved one drift by a pixel.
    private fun targetSize(maxSide: Int): Pair<Int, Int> {
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(original.width, original.height))
        return maxOf(1, Math.round(original.width * scale).toInt()) to
            maxOf(1, Math.round(original.height * scale).toInt())
    }

    // A single step from a much larger image aliases.
    private fun reduced(width: Int, height: Int, type: Int): BufferedImage {
        val halvings = if (type == BufferedImage.TYPE_INT_ARGB) alphaHalvings else opaqueHalvings
        var current = original
        var steps = 0
        while (current.width > 2 * width || current.height > 2 * height) {
            val stepWidth = maxOf(width, current.width / 2)
            val stepHeight = maxOf(height, current.height / 2)
            steps++
            // Exact halvings come before any clamped step and do not depend on the target: every size shares them.
            val exact = stepWidth == current.width / 2 && stepHeight == current.height / 2
            if (exact && steps < halvings.size) {
                current = halvings[steps]
                continue
            }
            current = resized(current, stepWidth, stepHeight, type, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            if (exact) {
                halvings.add(current)
            }
        }
        return current
    }
}
