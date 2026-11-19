package imageprocessor.shared.store

import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode
import imageprocessor.shared.tasks.NewTask
import imageprocessor.shared.tasks.StoredVariant
import imageprocessor.shared.tasks.TaskItem
import imageprocessor.shared.tasks.WriteOutcome

interface TaskRepository {
    fun create(task: NewTask): WriteOutcome

    fun get(id: String): TaskItem?

    fun markProcessing(id: String): WriteOutcome

    fun markDone(id: String, variants: List<StoredVariant>): WriteOutcome

    fun markInputRejected(id: String, code: InputRejectionCode): WriteOutcome

    fun markFailed(id: String, code: FailureCode): WriteOutcome
}
