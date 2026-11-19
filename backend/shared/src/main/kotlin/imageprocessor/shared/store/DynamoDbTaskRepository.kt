package imageprocessor.shared.store

import imageprocessor.shared.contract.models.FailureCode
import imageprocessor.shared.contract.models.InputRejectionCode
import imageprocessor.shared.tasks.NewTask
import imageprocessor.shared.tasks.StoredVariant
import imageprocessor.shared.tasks.TaskItem
import imageprocessor.shared.tasks.TaskState
import imageprocessor.shared.tasks.TaskStatus
import imageprocessor.shared.tasks.WriteOutcome
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest
import java.time.Clock
import java.time.temporal.ChronoUnit

class DynamoDbTaskRepository(
    private val client: DynamoDbClient,
    private val config: TaskTableConfig,
    private val clock: Clock = Clock.systemUTC(),
) : TaskRepository {
    override fun create(task: NewTask): WriteOutcome {
        val item = task.toItem(clock.instant().truncatedTo(ChronoUnit.SECONDS))
        val request = PutItemRequest.builder()
            .tableName(config.tableName)
            .item(newItemAttributes(item))
            .conditionExpression("attribute_not_exists(#id)")
            .expressionAttributeNames(mapOf("#id" to ID))
            .build()
        return applyWrite { client.putItem(request) }
    }

    override fun get(id: String): TaskItem? {
        val request = GetItemRequest.builder()
            .tableName(config.tableName)
            .key(mapOf(ID to text(id)))
            .consistentRead(true)
            .build()
        val response = client.getItem(request)
        if (!response.hasItem()) {
            return null
        }
        return taskItem(response.item())
    }

    override fun markProcessing(id: String): WriteOutcome = transition(id, TaskState.Processing)

    override fun markDone(id: String, variants: List<StoredVariant>): WriteOutcome =
        transition(id, TaskState.Done(variants))

    override fun markInputRejected(id: String, code: InputRejectionCode): WriteOutcome =
        transition(id, TaskState.InputRejected(code))

    override fun markFailed(id: String, code: FailureCode): WriteOutcome = transition(id, TaskState.Failed(code))

    // The first terminal status wins; the condition also keeps UpdateItem from creating an unknown id.
    private fun transition(id: String, state: TaskState): WriteOutcome {
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val changes = stateAttributes(state) + (UPDATED_AT to text(now.toString()))
        val names = changes.keys.associateBy { name -> "#$name" }
        val values = changes.mapKeys { (name, _) -> ":$name" } + mapOf(
            ":pending" to text(TaskStatus.PENDING_UPLOAD.name),
            ":processing" to text(TaskStatus.PROCESSING.name),
        )
        val updateExpression = changes.keys.joinToString(prefix = "SET ") { name -> "#$name = :$name" }

        val request = UpdateItemRequest.builder()
            .tableName(config.tableName)
            .key(mapOf(ID to text(id)))
            .updateExpression(updateExpression)
            .conditionExpression("#$STATUS IN (:pending, :processing)")
            .expressionAttributeNames(names)
            .expressionAttributeValues(values)
            .build()
        return applyWrite { client.updateItem(request) }
    }

    private fun applyWrite(call: () -> Unit): WriteOutcome =
        try {
            call()
            WriteOutcome.Written
        } catch (_: ConditionalCheckFailedException) {
            WriteOutcome.Skipped
        }
}
