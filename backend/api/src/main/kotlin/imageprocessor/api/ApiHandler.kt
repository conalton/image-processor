package imageprocessor.api

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse
import com.amazonaws.services.lambda.runtime.logging.LogLevel
import imageprocessor.api.contract.SpecConstraints
import imageprocessor.api.http.ApiRequest
import imageprocessor.api.http.JSON_MEDIA_TYPE
import imageprocessor.api.http.errorResponse
import imageprocessor.api.signing.LinkSigner
import imageprocessor.api.signing.UploadSigner
import imageprocessor.api.tasks.TaskCreator
import imageprocessor.api.tasks.TaskReader
import imageprocessor.shared.aws.AwsClients
import imageprocessor.shared.contract.models.ErrorCode
import imageprocessor.shared.store.DynamoDbTaskRepository
import software.amazon.awssdk.regions.Region
import java.net.URI
import java.util.Base64

class ApiHandler(private val router: ApiRouter) : RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {
    constructor() : this(createRouter(loadApiConfig(System.getenv())))

    override fun handleRequest(event: APIGatewayV2HTTPEvent, context: Context): APIGatewayV2HTTPResponse {
        val request = ApiRequest(
            method = event.requestContext.http.method,
            path = event.rawPath,
            body = readBody(event),
        )
        val response = try {
            router.route(request)
        } catch (e: Exception) {
            context.logger.log("${request.method} ${request.path} failed\n${e.stackTraceToString()}", LogLevel.ERROR)
            errorResponse(ErrorCode.INTERNAL_ERROR, "The request failed.")
        }
        return APIGatewayV2HTTPResponse.builder()
            .withStatusCode(response.status)
            .withHeaders(response.headers + ("Content-Type" to JSON_MEDIA_TYPE))
            .withBody(response.body)
            .build()
    }
}

// A body that is not valid base64 is treated as no body, which the task creation refuses as INVALID_REQUEST.
private fun readBody(event: APIGatewayV2HTTPEvent): String? {
    val body = event.body
    if (body == null || !event.isBase64Encoded) {
        return body
    }
    return try {
        String(Base64.getDecoder().decode(body), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun createRouter(config: ApiConfig): ApiRouter {
    val clients = AwsClients(Region.of(config.region))
    val tasks = DynamoDbTaskRepository(
        client = clients.dynamoDb(apiCallTimeout = config.sdkCallTimeout),
        config = config.taskTable,
    )
    val presigner = clients.s3Presigner(URI.create(config.s3PublicEndpoint))
    val signer = UploadSigner(presigner = presigner, bucket = config.imagesBucket)
    val links = LinkSigner(presigner = presigner, bucket = config.imagesBucket)
    val constraints = SpecConstraints()
    return ApiRouter(
        taskCreator = TaskCreator(tasks, signer, config.originalsPrefix, config.retention, constraints),
        taskReader = TaskReader(tasks, links),
    )
}
