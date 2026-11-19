package imageprocessor.api.tasks

import imageprocessor.api.signing.LinkSigner
import imageprocessor.shared.contract.models.DoneTask
import imageprocessor.shared.contract.models.FailedTask
import imageprocessor.shared.contract.models.Failure
import imageprocessor.shared.contract.models.FileInfo
import imageprocessor.shared.contract.models.InputRejectedTask
import imageprocessor.shared.contract.models.InputRejection
import imageprocessor.shared.contract.models.PendingTask
import imageprocessor.shared.contract.models.ProcessingTask
import imageprocessor.shared.contract.models.Task
import imageprocessor.shared.contract.models.Variant
import imageprocessor.shared.tasks.StoredVariant
import imageprocessor.shared.tasks.TaskItem
import imageprocessor.shared.tasks.TaskState
import kotlin.time.toKotlinInstant

fun toView(item: TaskItem, links: LinkSigner): Task =
    when (val state = item.state) {
        TaskState.PendingUpload -> PendingTask(id = item.id, createdAt = item.createdAt.toKotlinInstant())

        TaskState.Processing -> ProcessingTask(item.id)

        is TaskState.Done -> DoneTask(
            id = item.id,
            original = FileInfo(
                contentType = item.contentType,
                size = item.size,
                viewUrl = links.viewUrl(item.originalKey),
                downloadUrl = links.downloadUrl(
                    key = item.originalKey,
                    fileName = originalDownloadName(fileName = item.fileName, contentType = item.contentType),
                ),
            ),
            variants = state.variants.map { variant -> variantView(item, variant, links) },
        )

        is TaskState.InputRejected -> InputRejectedTask(id = item.id, error = InputRejection(state.code))

        is TaskState.Failed -> FailedTask(id = item.id, error = Failure(state.code))
    }

private fun variantView(item: TaskItem, variant: StoredVariant, links: LinkSigner): Variant =
    Variant(
        name = variant.name,
        label = variant.label,
        contentType = variant.contentType,
        width = variant.width,
        height = variant.height,
        size = variant.size,
        viewUrl = links.viewUrl(variant.key),
        downloadUrl = links.downloadUrl(
            key = variant.key,
            fileName = variantDownloadName(
                fileName = item.fileName,
                variantName = variant.name,
                contentType = variant.contentType,
            ),
        ),
    )
