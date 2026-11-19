package imageprocessor.api

import imageprocessor.shared.contract.models.ApiError
import imageprocessor.shared.contract.models.ContentType
import imageprocessor.shared.contract.models.CreateTaskResponse
import imageprocessor.shared.contract.models.DoneTask
import imageprocessor.shared.contract.models.ErrorCode
import imageprocessor.shared.contract.models.Task
import imageprocessor.shared.tasks.TEST_NOW
import imageprocessor.shared.tasks.TEST_TASK_ID
import imageprocessor.shared.tasks.TaskState
import imageprocessor.shared.tasks.doneState
import imageprocessor.shared.tasks.taskItem
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskEndpointsTest {
    @Test
    fun `a created task is stored as PENDING_UPLOAD with the name as given and the upload is signed for it`() {
        val api = TestApi()

        val response = api.call(
            "POST",
            "/api/tasks",
            """{"fileName":"my photo (1).png","contentType":"image/png","size":2048}""",
        )

        assertEquals(201, response.statusCode)
        val created = Json.decodeFromString(CreateTaskResponse.serializer(), response.body)
        val item = api.tasks.items.getValue(created.id)
        assertEquals(TaskState.PendingUpload, item.state)
        assertEquals("my photo (1).png", item.fileName)
        assertEquals("$TEST_ORIGINALS_PREFIX${created.id}", item.originalKey)
        assertContains(created.upload.url, "/${item.originalKey}?")
        assertEquals("image/png", created.upload.headers["content-type"])
        assertEquals("2048", created.upload.headers["content-length"])
        assertEquals("*", created.upload.headers["if-none-match"])
        assertEquals("inline", created.upload.headers["content-disposition"])
        assertEquals("private", created.upload.headers["cache-control"])
        // The page's UPLOAD_WAIT_LIMIT_MS (15 minutes, taskFlowMachine.ts) assumes an upload URL that expires after 10.
        assertEquals("600", queryParameter(created.upload.url, "X-Amz-Expires"))
    }

    @Test
    fun `a size given as a JSON string is read as the number`() {
        val api = TestApi()
        val body = """{"fileName":"a.png","contentType":"image/png","size":"2048"}"""

        val response = api.call("POST", "/api/tasks", body)

        assertEquals(201, response.statusCode)
        val created = Json.decodeFromString(CreateTaskResponse.serializer(), response.body)
        assertEquals(2048L, api.tasks.items.getValue(created.id).size)
    }

    @Test
    fun `a request the spec refuses names each refused field and its rule`() {
        val body = """{"fileName":"","contentType":"image/png","size":0}"""

        val response = TestApi().call("POST", "/api/tasks", body)

        assertEquals(400, response.statusCode)
        assertEquals(
            "The request does not match the spec: fileName: must match \"^[^\\p{Cc}\\p{Cs}\\uFFFD]{1,255}$\"; " +
                "size: must be greater than or equal to 1.",
            Json.decodeFromString(ApiError.serializer(), response.body).message,
        )
    }

    @Test
    fun `a base64 encoded create request is decoded and accepted, garbage is 400`() {
        val json = """{"fileName":"a.png","contentType":"image/png","size":5}"""
        val body = Base64.getEncoder().encodeToString(json.toByteArray())

        assertEquals(201, TestApi().call("POST", "/api/tasks", body, base64 = true).statusCode)

        val garbage = TestApi().call("POST", "/api/tasks", "not base64!", base64 = true)

        assertEquals(400, garbage.statusCode)
        assertEquals(ErrorCode.INVALID_REQUEST, Json.decodeFromString(ApiError.serializer(), garbage.body).code)
    }

    @Test
    fun `an unknown property in the create request is ignored and the task is created`() {
        val api = TestApi()
        val body = """{"fileName":"a.png","contentType":"image/png","size":5,"note":"x"}"""

        val response = api.call("POST", "/api/tasks", body)

        assertEquals(201, response.statusCode)
        assertEquals(1, api.tasks.items.size)
    }

    @Test
    fun `a declared content type in any case is stored and signed in lower case`() {
        for ((declared, expected) in listOf("IMAGE/JPEG" to "image/jpeg", "Image/Png" to "image/png")) {
            val api = TestApi()
            val body = """{"fileName":"a.img","contentType":"$declared","size":10}"""

            val response = api.call("POST", "/api/tasks", body)

            assertEquals(201, response.statusCode, declared)
            val created = Json.decodeFromString(CreateTaskResponse.serializer(), response.body)
            assertEquals(expected, api.tasks.items.getValue(created.id).contentType.value, declared)
            assertEquals(expected, created.upload.headers["content-type"], declared)
        }
    }

    @Test
    fun `the links of a finished task last 60 minutes and download the original and each variant under their names`() {
        val api = TestApi()
        api.tasks.items[TEST_TASK_ID] =
            taskItem(state = doneState(), fileName = "Отпуск \"1\" *.jpg", contentType = ContentType.IMAGE_JPEG)

        val response = api.call("GET", "/api/tasks/$TEST_TASK_ID")

        val task = Json.decodeFromString(Task.serializer(), response.body) as DoneTask
        val encodedStem = "%D0%9E%D1%82%D0%BF%D1%83%D1%81%D0%BA%20%221%22%20%2A"
        assertEquals(disposition("________1___.jpg", "$encodedStem.jpg"), dispositionOf(task.original.downloadUrl))
        assertEquals(
            listOf(
                disposition("________1___-thumbnail.jpg", "$encodedStem-thumbnail.jpg"),
                disposition("________1___-png.png", "$encodedStem-png.png"),
            ),
            task.variants.map { variant -> dispositionOf(variant.downloadUrl) },
        )
        assertContains(task.original.viewUrl, "/originals/$TEST_TASK_ID?")
        assertContains(task.variants.first().viewUrl, "/derived/$TEST_TASK_ID/thumbnail.jpg?")
        assertEquals("3600", queryParameter(task.original.viewUrl, "X-Amz-Expires"))
    }

    @Test
    fun `the original downloads with the extension of its signed type, whatever the user's name ends with`() {
        val api = TestApi()
        for (name in listOf("x.html", "x.png.html", "x.svg", "x.hta", "x.html.", ".html", "x")) {
            api.tasks.items[TEST_TASK_ID] =
                taskItem(state = doneState(), fileName = name, contentType = ContentType.IMAGE_PNG)

            val names = downloadNames(doneTask(api).original.downloadUrl)

            assertTrue(names.all { it.endsWith(".png") }, "$name: $names")
        }
        api.tasks.items[TEST_TASK_ID] =
            taskItem(state = doneState(), fileName = "x.html", contentType = ContentType.IMAGE_JPEG)

        assertEquals(listOf("x.jpg", "x.jpg"), downloadNames(doneTask(api).original.downloadUrl))
    }

    @Test
    fun `a task past its TTL attribute is 404 although the item is still there`() {
        val api = TestApi()
        val live = taskItem(state = TaskState.Processing)
        api.tasks.items[TEST_TASK_ID] = live.copy(expiresAt = TEST_NOW.plusSeconds(1))

        assertEquals(200, api.call("GET", "/api/tasks/$TEST_TASK_ID").statusCode)

        api.tasks.items[TEST_TASK_ID] = live.copy(expiresAt = TEST_NOW.minusSeconds(1))

        val response = api.call("GET", "/api/tasks/$TEST_TASK_ID")
        assertEquals(404, response.statusCode)
        assertEquals(ErrorCode.TASK_NOT_FOUND, Json.decodeFromString(ApiError.serializer(), response.body).code)
    }

    @Test
    fun `an upper-case task id finds the task`() {
        val api = TestApi()
        api.tasks.items[TEST_TASK_ID] = taskItem(state = TaskState.Processing)

        assertEquals(200, api.call("GET", "/api/tasks/${TEST_TASK_ID.uppercase()}").statusCode)
    }

    @Test
    fun `an unexpected failure is 500 without details and is logged with its stack trace`() {
        val tasks = FakeTaskRepository()
        tasks.failure = IllegalStateException("DynamoDB is not reachable")
        val api = TestApi(tasks)

        val response = api.call("GET", "/api/tasks/$TEST_TASK_ID")

        assertEquals(500, response.statusCode)
        assertEquals(ErrorCode.INTERNAL_ERROR, Json.decodeFromString(ApiError.serializer(), response.body).code)
        assertFalse(response.body.contains("DynamoDB is not reachable"))
        assertTrue(api.context.logged.single().contains("DynamoDB is not reachable"))
    }

    private fun disposition(fallback: String, encoded: String): String =
        "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded"

    private fun doneTask(api: TestApi): DoneTask =
        Json.decodeFromString(Task.serializer(), api.call("GET", "/api/tasks/$TEST_TASK_ID").body) as DoneTask

    private fun downloadNames(url: String): List<String> {
        val disposition = dispositionOf(url)
        val fallback = disposition.substringAfter("filename=\"").substringBefore('"')
        val encoded = disposition.substringAfter("filename*=UTF-8''")
        return listOf(fallback, URLDecoder.decode(encoded, Charsets.UTF_8))
    }

    private fun dispositionOf(url: String): String = queryParameter(url, "response-content-disposition")

    private fun queryParameter(url: String, name: String): String {
        val parameter = URI.create(url).rawQuery.split("&").first { it.startsWith("$name=") }
        return URLDecoder.decode(parameter.substringAfter('='), Charsets.UTF_8)
    }
}
