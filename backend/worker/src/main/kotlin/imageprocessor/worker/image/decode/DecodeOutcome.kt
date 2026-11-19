package imageprocessor.worker.image.decode

import imageprocessor.shared.contract.models.InputRejectionCode
import java.awt.image.BufferedImage

sealed interface DecodeOutcome {
    data class Decoded(val image: BufferedImage) : DecodeOutcome

    data class Rejected(
        val code: InputRejectionCode,
        val detail: String? = null,
    ) : DecodeOutcome

    data class Failed(val cause: Exception) : DecodeOutcome
}
