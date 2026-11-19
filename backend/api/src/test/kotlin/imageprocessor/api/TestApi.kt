package imageprocessor.api

import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse
import imageprocessor.api.contract.SpecConstraints
import imageprocessor.api.signing.LinkSigner
import imageprocessor.api.signing.UploadSigner
import imageprocessor.api.tasks.TaskCreator
import imageprocessor.api.tasks.TaskReader
import imageprocessor.shared.tasks.TEST_NOW
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

const val TEST_BUCKET = "images"
const val TEST_ORIGINALS_PREFIX = "originals/"
val TEST_RETENTION: Duration = Duration.ofDays(7)

private val SPEC_CONSTRAINTS = SpecConstraints()

/** Only DynamoDB is faked: presigning makes no request. */
class TestApi(val tasks: FakeTaskRepository = FakeTaskRepository()) {
    val context = TestContext()

    private val handler: ApiHandler

    init {
        val presigner = S3Presigner.builder()
            .region(Region.EU_NORTH_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .endpointOverride(URI.create("https://s3.test.invalid"))
            .build()
        handler = ApiHandler(
            ApiRouter(
                taskCreator = TaskCreator(
                    tasks,
                    UploadSigner(presigner, TEST_BUCKET),
                    TEST_ORIGINALS_PREFIX,
                    TEST_RETENTION,
                    SPEC_CONSTRAINTS,
                ),
                taskReader = TaskReader(
                    tasks,
                    LinkSigner(presigner, TEST_BUCKET),
                    Clock.fixed(TEST_NOW, ZoneOffset.UTC),
                ),
            ),
        )
    }

    fun call(method: String, path: String, body: String? = null, base64: Boolean = false): APIGatewayV2HTTPResponse {
        val http = APIGatewayV2HTTPEvent.RequestContext.Http.builder().withMethod(method).withPath(path).build()
        val event = APIGatewayV2HTTPEvent.builder()
            .withRawPath(path)
            .withBody(body)
            .withIsBase64Encoded(base64)
            .withRequestContext(APIGatewayV2HTTPEvent.RequestContext.builder().withHttp(http).build())
            .build()
        return handler.handleRequest(event, context)
    }
}

class TestContext : Context {
    val logged = mutableListOf<String>()

    override fun getAwsRequestId(): String = "test-request"

    override fun getLogGroupName(): String = "test-group"

    override fun getLogStreamName(): String = "test-stream"

    override fun getFunctionName(): String = "test-function"

    override fun getFunctionVersion(): String = "1"

    override fun getInvokedFunctionArn(): String = "test-arn"

    override fun getIdentity(): CognitoIdentity? = null

    override fun getClientContext(): ClientContext? = null

    override fun getRemainingTimeInMillis(): Int = 10_000

    override fun getMemoryLimitInMB(): Int = 1769

    override fun getLogger(): LambdaLogger =
        object : LambdaLogger {
            override fun log(message: String) {
                logged.add(message)
            }

            override fun log(message: ByteArray) {
                logged.add(String(message, Charsets.UTF_8))
            }
        }
}
