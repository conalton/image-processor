package imageprocessor.shared.store

import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode
import imageprocessor.shared.tasks.StoredVariant
import imageprocessor.shared.tasks.TaskItem
import imageprocessor.shared.tasks.TaskState
import imageprocessor.shared.tasks.TaskStatus
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.time.Instant

internal const val ID = "id"
internal const val STATUS = "status"
internal const val CREATED_AT = "createdAt"
internal const val UPDATED_AT = "updatedAt"

// The TTL attribute of the table; infra/dynamodb.tf names it too.
internal const val EXPIRES_AT = "expiresAt"
internal const val FILE_NAME = "fileName"
internal const val CONTENT_TYPE = "contentType"
internal const val SIZE = "size"
internal const val ORIGINAL_KEY = "originalKey"
internal const val ERROR = "error"
internal const val VARIANTS = "variants"

private const val ERROR_CODE = "code"
private const val VARIANT_NAME = "name"
private const val VARIANT_LABEL = "label"
private const val VARIANT_KEY = "key"
private const val VARIANT_WIDTH = "width"
private const val VARIANT_HEIGHT = "height"

internal fun text(value: String): AttributeValue = AttributeValue.fromS(value)

internal fun number(value: Long): AttributeValue = AttributeValue.fromN(value.toString())

internal fun errorAttribute(code: String): AttributeValue = AttributeValue.fromM(mapOf(ERROR_CODE to text(code)))

internal fun variantsAttribute(variants: List<StoredVariant>): AttributeValue =
    AttributeValue.fromL(
        variants.map { variant ->
            AttributeValue.fromM(variantAttributes(variant))
        },
    )

internal fun stateAttributes(state: TaskState): Map<String, AttributeValue> {
    val payload = when (state) {
        TaskState.PendingUpload, TaskState.Processing -> emptyMap()
        is TaskState.Done -> mapOf(VARIANTS to variantsAttribute(state.variants))
        is TaskState.InputRejected -> mapOf(ERROR to errorAttribute(state.code.value))
        is TaskState.Failed -> mapOf(ERROR to errorAttribute(state.code.value))
    }
    return mapOf(STATUS to text(state.status.name)) + payload
}

internal fun newItemAttributes(item: TaskItem): Map<String, AttributeValue> =
    mapOf(
        ID to text(item.id),
        CREATED_AT to text(item.createdAt.toString()),
        UPDATED_AT to text(item.updatedAt.toString()),
        EXPIRES_AT to number(item.expiresAt.epochSecond),
        FILE_NAME to text(item.fileName),
        CONTENT_TYPE to text(item.contentType.value),
        SIZE to number(item.size),
        ORIGINAL_KEY to text(item.originalKey),
    ) + stateAttributes(item.state)

internal fun taskItem(attributes: Map<String, AttributeValue>): TaskItem =
    TaskItem(
        id = attributes.getValue(ID).s(),
        state = taskState(attributes),
        createdAt = Instant.parse(attributes.getValue(CREATED_AT).s()),
        updatedAt = Instant.parse(attributes.getValue(UPDATED_AT).s()),
        expiresAt = Instant.ofEpochSecond(attributes.getValue(EXPIRES_AT).n().toLong()),
        fileName = attributes.getValue(FILE_NAME).s(),
        contentType = contentType(attributes.getValue(CONTENT_TYPE).s()),
        size = attributes.getValue(SIZE).n().toLong(),
        originalKey = attributes.getValue(ORIGINAL_KEY).s(),
    )

private fun taskState(attributes: Map<String, AttributeValue>): TaskState =
    when (TaskStatus.valueOf(attributes.getValue(STATUS).s())) {
        TaskStatus.PENDING_UPLOAD -> TaskState.PendingUpload

        TaskStatus.PROCESSING -> TaskState.Processing

        TaskStatus.DONE -> TaskState.Done(storedVariants(attributes))

        TaskStatus.INPUT_REJECTED -> {
            val code = errorCode(attributes)
            TaskState.InputRejected(
                checkNotNull(InputRejectionCode.fromValue(code)) {
                    "Unknown input rejection code $code"
                },
            )
        }

        TaskStatus.FAILED -> {
            val code = errorCode(attributes)
            TaskState.Failed(checkNotNull(FailureCode.fromValue(code)) { "Unknown failure code $code" })
        }
    }

private fun errorCode(attributes: Map<String, AttributeValue>): String =
    attributes.getValue(ERROR).m().getValue(ERROR_CODE).s()

private fun contentType(mimeType: String): ContentType =
    checkNotNull(ContentType.fromValue(mimeType)) { "Unknown content type $mimeType" }

private fun variantAttributes(variant: StoredVariant): Map<String, AttributeValue> =
    mapOf(
        VARIANT_NAME to text(variant.name),
        VARIANT_LABEL to text(variant.label),
        VARIANT_KEY to text(variant.key),
        CONTENT_TYPE to text(variant.contentType.value),
        VARIANT_WIDTH to number(variant.width.toLong()),
        VARIANT_HEIGHT to number(variant.height.toLong()),
        SIZE to number(variant.size),
    )

private fun storedVariants(attributes: Map<String, AttributeValue>): List<StoredVariant> {
    val variants = attributes[VARIANTS] ?: return emptyList()
    return variants.l().map { entry -> storedVariant(entry.m()) }
}

private fun storedVariant(attributes: Map<String, AttributeValue>): StoredVariant =
    StoredVariant(
        name = attributes.getValue(VARIANT_NAME).s(),
        label = attributes.getValue(VARIANT_LABEL).s(),
        key = attributes.getValue(VARIANT_KEY).s(),
        contentType = contentType(attributes.getValue(CONTENT_TYPE).s()),
        width = attributes.getValue(VARIANT_WIDTH).n().toInt(),
        height = attributes.getValue(VARIANT_HEIGHT).n().toInt(),
        size = attributes.getValue(SIZE).n().toLong(),
    )
