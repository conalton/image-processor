package imageprocessor.worker.deadletter

import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.tasks.TaskState
import imageprocessor.shared.tasks.taskItem
import imageprocessor.worker.processing.FakeTasks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DeadLetterMarkerTest {
    private val tasks = FakeTasks()
    private val marker = DeadLetterMarker(tasks, "originals/")

    private fun created(vararg keys: String): String {
        val records = keys.joinToString(",") { key ->
            """{"eventName":"ObjectCreated:Put","s3":{"bucket":{"name":"images"},"object":{"key":"$key","size":3}}}"""
        }
        return """{"Records":[$records]}"""
    }

    @Test
    fun `an unreadable body marks nothing, even the readable records next to the bad one`() {
        tasks.items["a"] = taskItem("a", TaskState.Processing)
        tasks.failTerminalWrites = true

        assertIs<DeadLetterOutcome.Unreadable>(marker.mark("not json"))
        assertIs<DeadLetterOutcome.Unreadable>(marker.mark(created("originals/a", "originals/%ZZ")))
        assertEquals(TaskState.Processing, tasks.items.getValue("a").state)
    }

    @Test
    fun `a repository error is thrown, not turned into Unreadable`() {
        tasks.items["a"] = taskItem("a", TaskState.Processing)
        tasks.failTerminalWrites = true

        assertFailsWith<IllegalStateException> { marker.mark(created("originals/a")) }
    }

    @Test
    fun `every record of the body is handed to markFailed`() {
        tasks.items["a"] = taskItem("a", TaskState.Processing)
        tasks.items["b"] = taskItem("b", TaskState.Processing)

        val outcome = marker.mark(created("originals/a", "originals/b"))

        assertEquals(DeadLetterOutcome.Read(marked = listOf("a", "b"), skipped = emptyList()), outcome)
        assertEquals(TaskState.Failed(FailureCode.PROCESSING_ERROR), tasks.items.getValue("a").state)
        assertEquals(TaskState.Failed(FailureCode.PROCESSING_ERROR), tasks.items.getValue("b").state)
    }
}
