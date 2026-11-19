package imageprocessor.worker.deadletter

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.logging.LogLevel
import imageprocessor.shared.aws.AwsClients
import imageprocessor.shared.store.DynamoDbTaskRepository
import software.amazon.awssdk.regions.Region

class DeadLetterHandler(private val marker: DeadLetterMarker) : RequestHandler<SQSEvent, Void?> {
    constructor() : this(createMarker(loadDeadLetterConfig(System.getenv())))

    override fun handleRequest(event: SQSEvent, context: Context): Void? {
        for (message in event.records) {
            try {
                logDeadLetter(context.logger, message.messageId, marker.mark(message.body))
            } catch (e: Exception) {
                context.logger.log(
                    "Message ${message.messageId} failed, it will be received again\n${e.stackTraceToString()}",
                    LogLevel.ERROR,
                )
                throw e
            }
        }
        return null
    }
}

private fun createMarker(config: DeadLetterConfig) =
    DeadLetterMarker(
        tasks = DynamoDbTaskRepository(
            client = AwsClients(Region.of(config.region)).dynamoDb(),
            config = config.taskTable,
        ),
        originalsPrefix = config.originalsPrefix,
    )
