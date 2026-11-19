package imageprocessor.api.contract

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse
import imageprocessor.api.FakeTaskRepository
import imageprocessor.api.TestApi
import imageprocessor.api.tasks.MAX_REQUEST_BODY_BYTES
import imageprocessor.shared.contract.models.ApiError
import imageprocessor.shared.contract.models.ErrorCode
import imageprocessor.shared.files.MAX_UPLOAD_BYTES
import imageprocessor.shared.tasks.TEST_TASK_ID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorContractTest {
    private val reader = SpecReader(specPath())
    private val validator = ContractValidator(specPath())

    @Test
    fun `another method on a known path is 405 with Allow, an undeclared path is 404`() {
        val operations = reader.operations()
        for (operation in operations) {
            val declared = operations.filter { other ->
                other.pathTemplate == operation.pathTemplate
            }.map { other -> other.method }
            for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE")) {
                if (method !in declared) {
                    val response = TestApi().call(method, operation.path)

                    assertEquals(405, response.statusCode, "$method ${operation.pathTemplate}")
                    assertEquals(ErrorCode.METHOD_NOT_ALLOWED, error(response).code)
                    assertEquals(
                        declared.joinToString(", "),
                        response.headers["Allow"],
                        "$method ${operation.pathTemplate}",
                    )
                }
            }
        }

        for (path in listOf("/", "/nope", "/api", "/api/tasks/", "/api/tasks/a/b")) {
            for (method in listOf("GET", "POST")) {
                val response = TestApi().call(method, path)

                assertEquals(404, response.statusCode, "$method $path")
                assertEquals(ErrorCode.NOT_FOUND, error(response).code, "$method $path")
            }
        }
    }

    @Test
    fun `a body that cannot be read is 400 INVALID_REQUEST`() {
        val bodies = mapOf(
            "no body" to null,
            "broken JSON" to """{"fileName":""",
            "not an object" to "[]",
            "size as a deeply nested list" to
                """{"fileName":"a.png","contentType":"image/png","size":${"[".repeat(8000)}}""",
            "fractional size" to """{"fileName":"a.png","contentType":"image/png","size":1.5}""",
            "size above a Long" to """{"fileName":"a.png","contentType":"image/png","size":9223372036854775808}""",
            "size that wraps to 5" to """{"fileName":"a.png","contentType":"image/png","size":18446744073709551621}""",
        )
        for ((name, body) in bodies) {
            val response = TestApi().call("POST", "/api/tasks", body)

            assertEquals(400, response.statusCode, name)
            assertEquals(ErrorCode.INVALID_REQUEST, error(response).code, name)
        }
    }

    @Test
    fun `a body above the size limit is 400 INVALID_REQUEST even when it is valid JSON`() {
        val valid = """{"fileName":"a.png","contentType":"image/png","size":5}"""
        val atLimit = valid + " ".repeat(MAX_REQUEST_BODY_BYTES - valid.length)
        val aboveLimit = atLimit + " "

        assertEquals(201, TestApi().call("POST", "/api/tasks", atLimit).statusCode)
        val response = TestApi().call("POST", "/api/tasks", aboveLimit)

        assertEquals(400, response.statusCode)
        assertEquals(ErrorCode.INVALID_REQUEST, error(response).code)
    }

    @Test
    fun `an unsupported content type is a valid request answered with UNSUPPORTED_CONTENT_TYPE`() {
        for (contentType in listOf("image/gif", "image/jpeg; charset=x", " image/png")) {
            val body = """{"fileName":"a.gif","contentType":"$contentType","size":5}"""

            val response = TestApi().call("POST", "/api/tasks", body)
            assertEquals(400, response.statusCode, contentType)
            assertEquals(ErrorCode.UNSUPPORTED_CONTENT_TYPE, error(response).code, contentType)
        }
    }

    @Test
    fun `a name or a plain integer size is 400 INVALID_REQUEST exactly when the spec refuses it, else a task is created`() {
        val emoji = "😀"
        val forbidden = listOf(
            """a\u0000b""",
            """a\tb""",
            """a\nb""",
            """a\u007fb""",
            """a\u0085b""",
            """a\u009fb""",
            """a\ud800b""",
            """a\udc00b""",
            """a\ufffdb""",
            "a\uFFFDb",
        )
        val names = listOf("", "a.png", "Отпуск 😀 a.b.png") + forbidden +
            listOf(1, 127, 128, 255, 256).flatMap { length -> listOf("a".repeat(length), emoji.repeat(length)) }
        val requests = names.map { name -> name to 5L } +
            listOf(-1L, 0L, 1L, 2L, MAX_UPLOAD_BYTES, MAX_UPLOAD_BYTES + 1).map { size -> "a.png" to size }

        val api = TestApi()
        var created = 0
        val refusedBySpec = requests.map { (name, size) ->
            val body = createBody(name, size)
            val specRefuses = validator.validateRequest("POST", "/api/tasks", "application/json", body).isNotEmpty()
            val response = api.call("POST", "/api/tasks", body)
            val expected = when {
                specRefuses -> ErrorCode.INVALID_REQUEST
                size > MAX_UPLOAD_BYTES -> ErrorCode.FILE_TOO_LARGE
                else -> null
            }
            val code = if (response.statusCode == 201) null else error(response).code
            if (code == null) {
                created++
            }
            assertEquals(expected, code, "fileName ${name.take(20)}, size $size")
            if (name in forbidden) {
                assertEquals(ErrorCode.INVALID_REQUEST, code, name)
            }
            specRefuses
        }
        assertEquals(setOf(true, false), refusedBySpec.toSet())
        assertEquals(created, api.tasks.items.size)
    }

    @Test
    fun `the upload limit of the spec is the limit of the handler`() {
        assertEquals(MAX_UPLOAD_BYTES, reader.property("FileInfo", "size").maximum.toLong())
        assertEquals(MAX_UPLOAD_BYTES, reader.property("ApiError", "maxUploadBytes").maximum.toLong())
    }

    @Test
    fun `maxUploadBytes is present with FILE_TOO_LARGE and with no other code`() {
        val failing = FakeTaskRepository()
        failing.failure = IllegalStateException("DynamoDB is not reachable")
        val responses = listOf(
            TestApi(failing).call("GET", "/api/tasks/$TEST_TASK_ID"),
            create("a.png", MAX_UPLOAD_BYTES + 1),
            TestApi().call("POST", "/api/tasks", """{"fileName":"a.gif","contentType":"image/gif","size":5}"""),
            TestApi().call("POST", "/api/tasks", "{"),
            TestApi().call("GET", "/api/tasks/not-an-id"),
            TestApi().call("GET", "/api/tasks/$TEST_TASK_ID"),
            TestApi().call("PUT", "/api/tasks"),
            TestApi().call("GET", "/nope"),
        )

        val codes = mutableSetOf<ErrorCode>()
        for (response in responses) {
            val error = error(response)
            codes.add(error.code)
            val expected = if (error.code == ErrorCode.FILE_TOO_LARGE) MAX_UPLOAD_BYTES else null
            assertEquals(expected, error.maxUploadBytes, error.code.name)
            val keyPresent = Json.parseToJsonElement(response.body).jsonObject.containsKey("maxUploadBytes")
            assertEquals(error.code == ErrorCode.FILE_TOO_LARGE, keyPresent, error.code.name)
        }
        assertEquals(
            setOf(
                ErrorCode.FILE_TOO_LARGE,
                ErrorCode.UNSUPPORTED_CONTENT_TYPE,
                ErrorCode.INVALID_REQUEST,
                ErrorCode.TASK_NOT_FOUND,
                ErrorCode.NOT_FOUND,
                ErrorCode.METHOD_NOT_ALLOWED,
                ErrorCode.INTERNAL_ERROR,
            ),
            codes,
        )
    }

    private fun create(fileName: String, size: Long): APIGatewayV2HTTPResponse =
        TestApi().call("POST", "/api/tasks", createBody(fileName, size))

    private fun createBody(fileName: String, size: Long): String =
        """{"fileName":"$fileName","contentType":"image/png","size":$size}"""

    private fun error(response: APIGatewayV2HTTPResponse): ApiError =
        Json.decodeFromString(ApiError.serializer(), response.body)
}
