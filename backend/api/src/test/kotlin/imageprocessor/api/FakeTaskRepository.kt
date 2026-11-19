package imageprocessor.api

import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode
import imageprocessor.shared.store.TaskRepository
import imageprocessor.shared.tasks.NewTask
import imageprocessor.shared.tasks.StoredVariant
import imageprocessor.shared.tasks.TEST_NOW
import imageprocessor.shared.tasks.TaskItem
import imageprocessor.shared.tasks.WriteOutcome

class FakeTaskRepository : TaskRepository {
    val items = mutableMapOf<String, TaskItem>()
    var failure: RuntimeException? = null

    override fun create(task: NewTask): WriteOutcome {
        failIfBroken()
        if (items.containsKey(task.id)) {
            return WriteOutcome.Skipped
        }
        items[task.id] = task.toItem(TEST_NOW)
        return WriteOutcome.Written
    }

    override fun get(id: String): TaskItem? {
        failIfBroken()
        return items[id]
    }

    override fun markProcessing(id: String): WriteOutcome = unsupported()

    override fun markDone(id: String, variants: List<StoredVariant>): WriteOutcome = unsupported()

    override fun markInputRejected(id: String, code: InputRejectionCode): WriteOutcome = unsupported()

    override fun markFailed(id: String, code: FailureCode): WriteOutcome = unsupported()

    private fun failIfBroken() {
        val error = failure
        if (error != null) {
            throw error
        }
    }

    private fun unsupported(): Nothing = throw UnsupportedOperationException("The API does not write task results")
}
