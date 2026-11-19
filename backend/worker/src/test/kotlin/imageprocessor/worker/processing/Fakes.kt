package imageprocessor.worker.processing

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode
import imageprocessor.shared.store.TaskRepository
import imageprocessor.shared.tasks.NewTask
import imageprocessor.shared.tasks.StoredVariant
import imageprocessor.shared.tasks.TaskItem
import imageprocessor.shared.tasks.TaskState
import imageprocessor.shared.tasks.WriteOutcome
import imageprocessor.worker.storage.ImageStorage
import imageprocessor.worker.storage.OriginalRead
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class FakeTasks : TaskRepository {
    val items = mutableMapOf<String, TaskItem>()
    var failTerminalWrites = false

    override fun create(task: NewTask): WriteOutcome = throw UnsupportedOperationException("not used by the worker")

    override fun get(id: String): TaskItem? = items[id]

    override fun markProcessing(id: String): WriteOutcome = transition(id, TaskState.Processing)

    override fun markDone(id: String, variants: List<StoredVariant>): WriteOutcome =
        terminalWrite(id, TaskState.Done(variants))

    override fun markInputRejected(id: String, code: InputRejectionCode): WriteOutcome =
        terminalWrite(id, TaskState.InputRejected(code))

    override fun markFailed(id: String, code: FailureCode): WriteOutcome = terminalWrite(id, TaskState.Failed(code))

    private fun terminalWrite(id: String, state: TaskState): WriteOutcome {
        check(!failTerminalWrites) { "DynamoDB is unavailable" }
        return transition(id, state)
    }

    private fun transition(id: String, state: TaskState): WriteOutcome {
        val current = items[id]
        if (current == null || !current.state.isUnfinished()) {
            return WriteOutcome.Skipped
        }
        items[id] = current.copy(state = state)
        return WriteOutcome.Written
    }

    private fun TaskState.isUnfinished(): Boolean = this is TaskState.PendingUpload || this is TaskState.Processing
}

class FakeStorage : ImageStorage {
    val objects = mutableMapOf<String, ByteArray>()
    var failRead = false
    var failWrite = false

    // The size S3 reports for the original; the body stays the stored bytes.
    var reportedSize: Long? = null

    override fun read(key: String): OriginalRead {
        check(!failRead) { "S3 is unavailable" }
        val bytes = objects[key] ?: return OriginalRead.Missing(IllegalStateException("No object $key"))
        return OriginalRead.Found(reportedSize ?: bytes.size.toLong(), ByteArrayInputStream(bytes))
    }

    override fun write(key: String, bytes: ByteArray, contentType: ContentType) {
        check(!failWrite) { "S3 is unavailable" }
        objects[key] = bytes
    }
}

class OversizedStorage(private val storedSize: Long) : ImageStorage {
    override fun read(key: String): OriginalRead {
        val body = object : InputStream() {
            override fun read(): Int = throw AssertionError("the body of an oversized original was read")
        }
        return OriginalRead.Found(storedSize, body)
    }

    override fun write(key: String, bytes: ByteArray, contentType: ContentType): Unit =
        throw AssertionError("nothing is written for an oversized original")
}

class BrokenBodyStorage : ImageStorage {
    var closed = false

    override fun read(key: String): OriginalRead {
        val body = object : InputStream() {
            override fun read(): Int = throw IOException("connection reset")

            override fun close() {
                closed = true
            }
        }
        return OriginalRead.Found(1024, body)
    }

    override fun write(key: String, bytes: ByteArray, contentType: ContentType): Unit =
        throw AssertionError("nothing is written for an unreadable original")
}
