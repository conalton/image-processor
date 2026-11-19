package imageprocessor.shared.tasks

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode
import java.time.Instant

const val TEST_TASK_ID = "0b8f2c5e-6c1a-4d0e-9a3b-7a1c2d3e4f50"
val TEST_NOW: Instant = Instant.parse("2026-10-20T12:00:00Z")

fun taskItem(
    id: String = TEST_TASK_ID,
    state: TaskState = TaskState.PendingUpload,
    fileName: String = "photo.png",
    contentType: ContentType = ContentType.IMAGE_PNG,
    expiresAt: Instant = TEST_NOW.plusSeconds(3600),
): TaskItem =
    TaskItem(
        id = id,
        state = state,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW,
        expiresAt = expiresAt,
        fileName = fileName,
        contentType = contentType,
        size = 1024,
        originalKey = "originals/$id",
    )

fun taskStates(id: String = TEST_TASK_ID): List<TaskState> =
    listOf(
        TaskState.PendingUpload,
        TaskState.Processing,
        doneState(id),
        TaskState.InputRejected(InputRejectionCode.UNDECODABLE_IMAGE),
        TaskState.Failed(FailureCode.PROCESSING_ERROR),
    )

fun doneState(id: String = TEST_TASK_ID): TaskState.Done =
    TaskState.Done(
        listOf(
            StoredVariant(
                name = "thumbnail",
                label = "Thumbnail",
                key = "derived/$id/thumbnail.jpg",
                contentType = ContentType.IMAGE_JPEG,
                width = 200,
                height = 150,
                size = 8192,
            ),
            StoredVariant(
                name = "png",
                label = "PNG",
                key = "derived/$id/png.png",
                contentType = ContentType.IMAGE_PNG,
                width = 1024,
                height = 768,
                size = 400_000,
            ),
        ),
    )
