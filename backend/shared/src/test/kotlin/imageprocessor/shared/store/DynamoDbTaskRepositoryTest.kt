package imageprocessor.shared.store

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode
import imageprocessor.shared.tasks.NewTask
import imageprocessor.shared.tasks.StoredVariant
import imageprocessor.shared.tasks.TaskState
import imageprocessor.shared.tasks.WriteOutcome
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// A table per test: JUnit creates one instance per test.
class DynamoDbTaskRepositoryTest {
    private val now = Instant.parse("2026-10-20T12:00:00Z")
    private val retention = Duration.ofDays(7)
    private val client = dynamoDbLocalClient()
    private val config = createTaskTable(client)
    private val store = DynamoDbTaskRepository(client, config, Clock.fixed(now, ZoneOffset.UTC))

    private val variant = StoredVariant(
        name = "thumbnail",
        label = "Thumbnail",
        key = "derived/id/thumbnail.png",
        contentType = ContentType.IMAGE_PNG,
        width = 128,
        height = 96,
        size = 4096,
    )

    private val storedDone = TaskState.Done(listOf(variant))
    private val storedRejected = TaskState.InputRejected(InputRejectionCode.UNDECODABLE_IMAGE)
    private val storedFailed = TaskState.Failed(FailureCode.PROCESSING_ERROR)

    @Test
    fun `a second create for the same id is skipped and keeps the first item`() {
        val id = newId()

        assertEquals(WriteOutcome.Written, store.create(newTask(id, fileName = "first.jpg")))
        assertEquals(WriteOutcome.Skipped, store.create(newTask(id, fileName = "second.jpg")))

        assertEquals("first.jpg", store.get(id)?.fileName)
    }

    @Test
    fun `markProcessing is written from the unfinished states and skipped from the terminal ones`() {
        val expected = mapOf(
            TaskState.PendingUpload to WriteOutcome.Written,
            TaskState.Processing to WriteOutcome.Written,
            storedDone to WriteOutcome.Skipped,
            storedRejected to WriteOutcome.Skipped,
            storedFailed to WriteOutcome.Skipped,
        )

        for ((state, outcome) in expected) {
            val id = taskIn(state)

            assertEquals(outcome, store.markProcessing(id), "markProcessing from $state")

            val stateAfter = if (outcome == WriteOutcome.Written) TaskState.Processing else state
            assertEquals(stateAfter, store.get(id)?.state, "state after markProcessing from $state")
        }
        val missingId = newId()
        assertEquals(WriteOutcome.Skipped, store.markProcessing(missingId), "markProcessing without an item")
        assertNull(store.get(missingId))
    }

    @Test
    fun `each terminal write stores its payload and the time of the write`() {
        val later = now.plusSeconds(600)
        val laterStore = DynamoDbTaskRepository(client, config, Clock.fixed(later, ZoneOffset.UTC))

        val doneId = taskIn(TaskState.Processing)
        assertEquals(WriteOutcome.Written, laterStore.markDone(doneId, listOf(variant)))
        val done = assertNotNull(store.get(doneId))
        assertEquals(TaskState.Done(listOf(variant)), done.state)
        assertEquals(later, done.updatedAt)

        val rejectedId = taskIn(TaskState.Processing)
        assertEquals(WriteOutcome.Written, laterStore.markInputRejected(rejectedId, InputRejectionCode.FILE_TOO_LARGE))
        val rejected = assertNotNull(store.get(rejectedId))
        assertEquals(TaskState.InputRejected(InputRejectionCode.FILE_TOO_LARGE), rejected.state)
        assertEquals(later, rejected.updatedAt)

        val failedId = taskIn(TaskState.PendingUpload)
        assertEquals(WriteOutcome.Written, laterStore.markFailed(failedId, FailureCode.PROCESSING_ERROR))
        val failed = assertNotNull(store.get(failedId))
        assertEquals(TaskState.Failed(FailureCode.PROCESSING_ERROR), failed.state)
        assertEquals(later, failed.updatedAt)
    }

    @Test
    fun `a terminal write is skipped from every terminal state and leaves the item unchanged`() {
        val laterStore = DynamoDbTaskRepository(client, config, Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC))
        // Payloads differ from the stored states: a wrongly applied write then shows in more than updatedAt.
        val writes = listOf(
            TaskState.Done(emptyList()),
            TaskState.InputRejected(InputRejectionCode.FILE_TOO_LARGE),
            TaskState.Failed(FailureCode.ORIGINAL_MISSING),
        )

        for (write in writes) {
            for (state in listOf(storedDone, storedRejected, storedFailed)) {
                val id = taskIn(state)
                val before = assertNotNull(store.get(id))

                assertEquals(WriteOutcome.Skipped, write(laterStore, write, id), "$write from $state")

                assertEquals(before, store.get(id), "item after $write from $state")
            }
        }
    }

    @Test
    fun `the TTL attribute holds the creation time plus the retention`() {
        val id = newId()
        store.create(newTask(id))

        val expiresAt = now.plus(retention)
        assertEquals(expiresAt.epochSecond, rawItem(id).getValue(EXPIRES_AT).n().toLong())

        val item = assertNotNull(store.get(id))
        assertEquals(expiresAt, item.expiresAt)
        assertFalse(item.isExpired(expiresAt))
        assertTrue(item.isExpired(expiresAt.plusSeconds(1)))
    }

    // Items already in the table were written with these values: the content type as a MIME type, codes as their names.
    @Test
    fun `stored statuses, content types and codes keep the form of earlier items`() {
        val doneId = taskIn(storedDone)
        val rejectedId = newId()
        store.create(newTask(rejectedId))
        store.markInputRejected(rejectedId, InputRejectionCode.FILE_TOO_LARGE)
        val failedId = newId()
        store.create(newTask(failedId))
        store.markFailed(failedId, FailureCode.ORIGINAL_MISSING)

        val done = rawItem(doneId)
        assertEquals("DONE", done.getValue(STATUS).s())
        assertEquals("image/jpeg", done.getValue(CONTENT_TYPE).s())
        assertEquals("image/png", done.getValue(VARIANTS).l().single().m().getValue(CONTENT_TYPE).s())
        assertEquals("FILE_TOO_LARGE", rawItem(rejectedId).getValue(ERROR).m().getValue("code").s())
        assertEquals("ORIGINAL_MISSING", rawItem(failedId).getValue(ERROR).m().getValue("code").s())
    }

    private fun rawItem(id: String): Map<String, AttributeValue> =
        client.getItem { request -> request.tableName(config.tableName).key(mapOf(ID to text(id))) }.item()

    private fun newId(): String = UUID.randomUUID().toString()

    private fun newTask(id: String, fileName: String = "holiday.jpg"): NewTask =
        NewTask(
            id = id,
            fileName = fileName,
            contentType = ContentType.IMAGE_JPEG,
            size = 3_145_728,
            originalKey = "originals/$id",
            retention = retention,
        )

    private fun taskIn(state: TaskState): String {
        val id = newId()
        store.create(newTask(id))
        if (state != TaskState.PendingUpload) {
            write(store, state, id)
        }
        return id
    }

    private fun write(store: DynamoDbTaskRepository, state: TaskState, id: String): WriteOutcome =
        when (state) {
            TaskState.PendingUpload -> throw IllegalArgumentException("no write sets $state")
            TaskState.Processing -> store.markProcessing(id)
            is TaskState.Done -> store.markDone(id, state.variants)
            is TaskState.InputRejected -> store.markInputRejected(id, state.code)
            is TaskState.Failed -> store.markFailed(id, state.code)
        }
}
