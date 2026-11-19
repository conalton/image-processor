package imageprocessor.worker.processing

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode
import imageprocessor.shared.files.MAX_UPLOAD_BYTES
import imageprocessor.shared.files.fileExtension
import imageprocessor.shared.store.TaskRepository
import imageprocessor.shared.tasks.StoredVariant
import imageprocessor.shared.tasks.TaskItem
import imageprocessor.shared.tasks.WriteOutcome
import imageprocessor.worker.image.decode.DecodeOutcome
import imageprocessor.worker.image.decode.decodeImage
import imageprocessor.worker.image.render.EncodedVariant
import imageprocessor.worker.image.render.renderVariants
import imageprocessor.worker.image.transform.ImageTransformation
import imageprocessor.worker.image.transform.VARIANT_CATALOGUE
import imageprocessor.worker.storage.ImageStorage
import imageprocessor.worker.storage.OriginalRead
import java.awt.image.BufferedImage
import java.time.Clock

class TaskProcessor(
    private val tasks: TaskRepository,
    private val storage: ImageStorage,
    private val derivedPrefix: String,
    private val decoder: (ByteArray, ContentType) -> DecodeOutcome = ::decodeImage,
    private val renderer: (BufferedImage, List<ImageTransformation>) -> List<EncodedVariant> = ::renderVariants,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun process(id: String): ProcessingOutcome {
        val item = tasks.get(id) ?: return ProcessingOutcome.Skipped(SkipReason.ITEM_MISSING)
        if (item.isExpired(clock.instant())) {
            return ProcessingOutcome.Skipped(SkipReason.ITEM_EXPIRED)
        }
        if (tasks.markProcessing(id) == WriteOutcome.Skipped) {
            return ProcessingOutcome.Skipped(SkipReason.ALREADY_FINISHED)
        }

        return when (val original = storage.read(item.originalKey)) {
            is OriginalRead.Missing -> fail(
                id,
                FailureCode.ORIGINAL_MISSING,
                ProcessingStage.READ_ORIGINAL,
                original.cause,
            )

            is OriginalRead.Found -> processOriginal(item, original)
        }
    }

    private fun processOriginal(item: TaskItem, original: OriginalRead.Found): ProcessingOutcome {
        // A second boundary after S3's check of the signed Content-Length, before any byte is read into memory.
        if (original.storedSize > MAX_UPLOAD_BYTES) {
            original.body.close()
            return reject(item.id, InputRejectionCode.FILE_TOO_LARGE)
        }
        val bytes = original.body.use { stream -> stream.readAllBytes() }
        return decodeAndStore(item, bytes)
    }

    private fun decodeAndStore(item: TaskItem, bytes: ByteArray): ProcessingOutcome =
        when (val decoded = decoder(bytes, item.contentType)) {
            is DecodeOutcome.Rejected -> reject(item.id, decoded.code, decoded.detail)

            is DecodeOutcome.Failed -> fail(
                item.id,
                FailureCode.PROCESSING_ERROR,
                ProcessingStage.DECODE,
                decoded.cause,
            )

            is DecodeOutcome.Decoded -> renderAndStore(item, decoded.image)
        }

    private fun renderAndStore(item: TaskItem, image: BufferedImage): ProcessingOutcome {
        val rendered = try {
            renderer(image, VARIANT_CATALOGUE)
        } catch (error: Exception) {
            return fail(item.id, FailureCode.PROCESSING_ERROR, ProcessingStage.TRANSFORM, error)
        }

        // Files first, the terminal write last: a task is DONE only when every file exists.
        val stored = mutableListOf<StoredVariant>()
        for (variant in rendered) {
            val key = variantKey(derivedPrefix, item.id, variant.name, variant.contentType.fileExtension)
            storage.write(key, variant.bytes, variant.contentType)
            stored.add(
                StoredVariant(
                    name = variant.name,
                    label = variant.label,
                    key = key,
                    contentType = variant.contentType,
                    width = variant.width,
                    height = variant.height,
                    size = variant.size,
                ),
            )
        }
        return finish(tasks.markDone(item.id, stored), ProcessingOutcome.Done(stored))
    }

    private fun reject(id: String, code: InputRejectionCode, detail: String? = null): ProcessingOutcome =
        finish(tasks.markInputRejected(id, code), ProcessingOutcome.InputRejected(code, detail))

    private fun fail(id: String, code: FailureCode, stage: ProcessingStage, cause: Exception): ProcessingOutcome =
        finish(tasks.markFailed(id, code), ProcessingOutcome.Failed(code, stage, cause))

    // The first terminal write wins; losing the race is not an error.
    private fun finish(written: WriteOutcome, outcome: ProcessingOutcome): ProcessingOutcome =
        when (written) {
            WriteOutcome.Written -> outcome
            WriteOutcome.Skipped -> ProcessingOutcome.Skipped(SkipReason.ALREADY_FINISHED)
        }
}

private fun variantKey(derivedPrefix: String, id: String, variantName: String, extension: String): String =
    "$derivedPrefix$id/$variantName.$extension"
