package imageprocessor.worker.processing

import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode
import imageprocessor.shared.tasks.StoredVariant

enum class ProcessingStage {
    READ_ORIGINAL,
    DECODE,
    TRANSFORM,
}

enum class SkipReason {
    ITEM_MISSING,
    ITEM_EXPIRED,
    ALREADY_FINISHED,
}

// Every outcome means the message can be acknowledged; infrastructure errors are thrown instead.
sealed interface ProcessingOutcome {
    data class Skipped(val reason: SkipReason) : ProcessingOutcome

    data class Done(val variants: List<StoredVariant>) : ProcessingOutcome

    data class InputRejected(
        val code: InputRejectionCode,
        val detail: String? = null,
    ) : ProcessingOutcome

    data class Failed(
        val code: FailureCode,
        val stage: ProcessingStage,
        val cause: Exception,
    ) : ProcessingOutcome
}
