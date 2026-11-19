package imageprocessor.shared.tasks

import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode

sealed interface TaskState {
    val status: TaskStatus

    data object PendingUpload : TaskState {
        override val status = TaskStatus.PENDING_UPLOAD
    }

    data object Processing : TaskState {
        override val status = TaskStatus.PROCESSING
    }

    data class Done(val variants: List<StoredVariant>) : TaskState {
        override val status = TaskStatus.DONE
    }

    data class InputRejected(val code: InputRejectionCode) : TaskState {
        override val status = TaskStatus.INPUT_REJECTED
    }

    data class Failed(val code: FailureCode) : TaskState {
        override val status = TaskStatus.FAILED
    }
}
