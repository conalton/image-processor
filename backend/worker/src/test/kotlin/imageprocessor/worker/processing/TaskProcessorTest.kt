package imageprocessor.worker.processing

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode
import imageprocessor.shared.files.MAX_UPLOAD_BYTES
import imageprocessor.shared.tasks.TEST_NOW
import imageprocessor.shared.tasks.TaskState
import imageprocessor.shared.tasks.doneState
import imageprocessor.shared.tasks.taskItem
import imageprocessor.worker.image.decode.DecodeOutcome
import imageprocessor.worker.image.decode.decodeImage
import imageprocessor.worker.image.fixtures.encodeAs
import imageprocessor.worker.image.fixtures.testImage
import imageprocessor.worker.image.render.EncodedVariant
import imageprocessor.worker.image.render.renderVariants
import imageprocessor.worker.image.transform.ImageTransformation
import imageprocessor.worker.image.transform.VARIANT_CATALOGUE
import imageprocessor.worker.storage.ImageStorage
import java.awt.image.BufferedImage
import java.io.IOException
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskProcessorTest {
    private val id = "0b8f2c5e"
    private val tasks = FakeTasks()
    private val storage = FakeStorage()

    private fun processor(
        storage: ImageStorage = this.storage,
        decoder: (ByteArray, ContentType) -> DecodeOutcome = ::decodeImage,
        renderer: (BufferedImage, List<ImageTransformation>) -> List<EncodedVariant> = ::renderVariants,
    ) = TaskProcessor(
        tasks = tasks,
        storage = storage,
        derivedPrefix = "derived/",
        decoder = decoder,
        renderer = renderer,
        clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC),
    )

    private fun givenPngOriginal() {
        tasks.items[id] = taskItem(id)
        storage.objects["originals/$id"] = encodeAs(testImage(), "png")
    }

    @Test
    fun `writes every variant, then marks the task done`() {
        givenPngOriginal()

        val outcome = processor().process(id)

        val done = assertIs<ProcessingOutcome.Done>(outcome)
        assertTrue(done.variants.size > 1, "the task asks for several versions")
        assertEquals(VARIANT_CATALOGUE.map { it.name }, done.variants.map { it.name })
        assertEquals(TaskState.Done(done.variants), tasks.items.getValue(id).state)
        assertEquals(done.variants.map { it.key }.toSet(), storage.objects.keys - "originals/$id")
        assertTrue(done.variants.all { it.key.startsWith("derived/$id/") })
    }

    @Test
    fun `a bug in the decoder or the renderer fails the task and is acknowledged`() {
        val bugs = mapOf(
            ProcessingStage.DECODE to
                processor(decoder = { _, _ -> DecodeOutcome.Failed(IllegalStateException("bug")) }),
            ProcessingStage.TRANSFORM to processor(renderer = { _, _ -> throw IllegalStateException("bug") }),
        )

        for ((stage, processor) in bugs) {
            givenPngOriginal()

            val failed = assertIs<ProcessingOutcome.Failed>(processor.process(id))

            assertEquals(FailureCode.PROCESSING_ERROR, failed.code)
            assertEquals(stage, failed.stage)
            assertEquals(TaskState.Failed(FailureCode.PROCESSING_ERROR), tasks.items.getValue(id).state)
        }
    }

    @Test
    fun `a missing original fails the task with ORIGINAL_MISSING`() {
        tasks.items[id] = taskItem(id)

        val outcome = processor().process(id)

        val failed = assertIs<ProcessingOutcome.Failed>(outcome)
        assertEquals(FailureCode.ORIGINAL_MISSING, failed.code)
        assertEquals(TaskState.Failed(FailureCode.ORIGINAL_MISSING), tasks.items.getValue(id).state)
    }

    @Test
    fun `a failing read is thrown and leaves no terminal status`() {
        givenPngOriginal()
        storage.failRead = true

        assertFailsWith<IllegalStateException> { processor().process(id) }

        assertEquals(TaskState.Processing, tasks.items.getValue(id).state)
    }

    @Test
    fun `a failing write is thrown, and the next attempt finishes the task`() {
        val failures = listOf<(Boolean) -> Unit>({ storage.failWrite = it }, { tasks.failTerminalWrites = it })

        for (setFailing in failures) {
            givenPngOriginal()
            setFailing(true)

            assertFailsWith<IllegalStateException> { processor().process(id) }
            assertEquals(TaskState.Processing, tasks.items.getValue(id).state)

            setFailing(false)
            assertIs<ProcessingOutcome.Done>(processor().process(id))
            assertIs<TaskState.Done>(tasks.items.getValue(id).state)
        }
    }

    @Test
    fun `an original above the upload limit is rejected unread, exactly the limit is processed`() {
        tasks.items[id] = taskItem(id)

        val outcome = processor(storage = OversizedStorage(MAX_UPLOAD_BYTES + 1)).process(id)

        assertEquals(ProcessingOutcome.InputRejected(InputRejectionCode.FILE_TOO_LARGE), outcome)
        assertEquals(TaskState.InputRejected(InputRejectionCode.FILE_TOO_LARGE), tasks.items.getValue(id).state)

        givenPngOriginal()
        storage.reportedSize = MAX_UPLOAD_BYTES

        assertIs<ProcessingOutcome.Done>(processor().process(id))
    }

    @Test
    fun `an image that cannot be used is rejected with its code`() {
        val rejections = mapOf(
            InputRejectionCode.UNDECODABLE_IMAGE to ("not an image".toByteArray() to ContentType.IMAGE_PNG),
            InputRejectionCode.CONTENT_TYPE_MISMATCH to (encodeAs(testImage(), "png") to ContentType.IMAGE_JPEG),
        )

        for ((code, upload) in rejections) {
            tasks.items[id] = taskItem(id, contentType = upload.second)
            storage.objects["originals/$id"] = upload.first

            val outcome = processor().process(id)

            assertEquals(code, assertIs<ProcessingOutcome.InputRejected>(outcome).code)
            assertEquals(TaskState.InputRejected(code), tasks.items.getValue(id).state)
        }
    }

    @Test
    fun `an original body that throws is a retriable error and the stream is closed`() {
        tasks.items[id] = taskItem(id)
        val broken = BrokenBodyStorage()

        assertFailsWith<IOException> { processor(storage = broken).process(id) }

        assertTrue(broken.closed)
        assertEquals(TaskState.Processing, tasks.items.getValue(id).state)
    }

    @Test
    fun `a message for a missing, expired or finished item is skipped and writes nothing`() {
        assertEquals(ProcessingOutcome.Skipped(SkipReason.ITEM_MISSING), processor().process(id))
        assertTrue(storage.objects.isEmpty())

        givenPngOriginal()
        tasks.items[id] = taskItem(id, expiresAt = TEST_NOW.minusSeconds(1))

        assertEquals(ProcessingOutcome.Skipped(SkipReason.ITEM_EXPIRED), processor().process(id))
        assertEquals(TaskState.PendingUpload, tasks.items.getValue(id).state)

        tasks.items[id] = taskItem(id, state = doneState(id))

        assertEquals(ProcessingOutcome.Skipped(SkipReason.ALREADY_FINISHED), processor().process(id))
        assertEquals(setOf("originals/$id"), storage.objects.keys)
    }
}
