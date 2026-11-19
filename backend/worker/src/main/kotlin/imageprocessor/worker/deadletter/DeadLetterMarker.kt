package imageprocessor.worker.deadletter

import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.store.TaskRepository
import imageprocessor.shared.tasks.WriteOutcome
import imageprocessor.worker.decodeS3Notification

sealed interface DeadLetterOutcome {
    data class Unreadable(val cause: IllegalArgumentException) : DeadLetterOutcome
    data class Read(
        val marked: List<String>,
        val skipped: List<String>,
    ) : DeadLetterOutcome
}

class DeadLetterMarker(
    private val tasks: TaskRepository,
    private val originalsPrefix: String,
) {
    fun mark(body: String): DeadLetterOutcome {
        // Only the decoding is guarded: an exception from the repository must make the message come back.
        val ids = try {
            decodeS3Notification(body).taskIds(originalsPrefix)
        } catch (e: IllegalArgumentException) {
            return DeadLetterOutcome.Unreadable(e)
        }
        val marked = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (id in ids) {
            when (tasks.markFailed(id, FailureCode.PROCESSING_ERROR)) {
                WriteOutcome.Written -> marked.add(id)
                WriteOutcome.Skipped -> skipped.add(id)
            }
        }
        return DeadLetterOutcome.Read(marked = marked, skipped = skipped)
    }
}
