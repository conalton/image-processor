package imageprocessor.worker

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.logging.LogLevel
import imageprocessor.shared.aws.AwsClients
import imageprocessor.shared.store.DynamoDbTaskRepository
import imageprocessor.worker.processing.TaskProcessor
import imageprocessor.worker.processing.logOutcome
import imageprocessor.worker.storage.S3ImageStorage
import software.amazon.awssdk.regions.Region

class WorkerHandler(
    private val processor: TaskProcessor,
    private val originalsPrefix: String,
) : RequestHandler<SQSEvent, Void?> {
    constructor() : this(loadWorkerConfig(System.getenv()))

    private constructor(config: WorkerConfig) : this(createProcessor(config), config.originalsPrefix)

    // An exception leaves the message in the queue; returning acknowledges it.
    override fun handleRequest(event: SQSEvent, context: Context): Void? {
        for (message in event.records) {
            val ids = decodeS3Notification(message.body).taskIds(originalsPrefix)
            if (ids.isEmpty()) {
                context.logger.log("Message ${message.messageId} names no original: ignored", LogLevel.INFO)
            }
            for (id in ids) {
                context.logger.log("Task $id: attempt ${message.attributes["ApproximateReceiveCount"]}", LogLevel.INFO)
                logOutcome(context.logger, id, processor.process(id))
            }
        }
        return null
    }
}

private fun createProcessor(config: WorkerConfig): TaskProcessor {
    val clients = AwsClients(Region.of(config.region))
    return TaskProcessor(
        tasks = DynamoDbTaskRepository(client = clients.dynamoDb(), config = config.taskTable),
        storage = S3ImageStorage(client = clients.s3(), bucket = config.imagesBucket),
        derivedPrefix = config.derivedPrefix,
    )
}
