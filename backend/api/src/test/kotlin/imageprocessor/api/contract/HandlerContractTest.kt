package imageprocessor.api.contract

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse
import imageprocessor.api.FakeTaskRepository
import imageprocessor.api.TestApi
import imageprocessor.shared.tasks.TEST_TASK_ID
import imageprocessor.shared.tasks.TaskState
import imageprocessor.shared.tasks.TaskStatus
import imageprocessor.shared.tasks.doneState
import imageprocessor.shared.tasks.taskItem
import imageprocessor.shared.tasks.taskStates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private const val CREATE_BODY = """{"fileName":"holiday.jpg","contentType":"image/jpeg","size":3145728}"""
private const val TOO_LARGE_BODY = """{"fileName":"holiday.jpg","contentType":"image/jpeg","size":31457280}"""

class HandlerContractTest {
    private val reader = SpecReader(specPath())
    private val validator = ContractValidator(specPath())

    private class Exchange(
        val method: String,
        val path: String,
        val response: APIGatewayV2HTTPResponse,
    )

    // One scenario per (method, path, status) of the spec; the first test fails on a missing or extra one.
    private val scenarios: Map<String, () -> Exchange> = mapOf(
        "POST /api/tasks 201" to { exchange(TestApi(), "POST", "/api/tasks", CREATE_BODY) },
        "POST /api/tasks 400" to { exchange(TestApi(), "POST", "/api/tasks", TOO_LARGE_BODY) },
        "POST /api/tasks 500" to { exchange(brokenApi(), "POST", "/api/tasks", CREATE_BODY) },
        "GET /api/tasks/{id} 200" to { exchange(seededApi(doneState()), "GET", "/api/tasks/$TEST_TASK_ID") },
        "GET /api/tasks/{id} 400" to { exchange(TestApi(), "GET", "/api/tasks/not-an-id") },
        "GET /api/tasks/{id} 404" to { exchange(TestApi(), "GET", "/api/tasks/$TEST_TASK_ID") },
        "GET /api/tasks/{id} 500" to { exchange(brokenApi(), "GET", "/api/tasks/$TEST_TASK_ID") },
    )

    @Test
    fun `every operation and status of the spec is answered by the real handler and matches the spec`() {
        val specPairs = reader.operations()
            .flatMap { operation ->
                operation.statuses.map { status -> "${operation.method} ${operation.pathTemplate} $status" }
            }
            .toSet()
        assertEquals(specPairs, scenarios.keys)

        for ((name, run) in scenarios) {
            val exchange = run()
            assertEquals(name.substringAfterLast(' ').toInt(), exchange.response.statusCode, name)
            assertEquals(emptyList(), violations(exchange), name)
        }
    }

    @Test
    fun `a task in each of the five states is returned as the spec describes it`() {
        val states = taskStates()
        assertEquals(TaskStatus.entries.toSet(), states.map { state -> state.status }.toSet())

        for (state in states) {
            val exchange = exchange(seededApi(state), "GET", "/api/tasks/$TEST_TASK_ID")

            assertEquals(200, exchange.response.statusCode, state.status.name)
            assertEquals(emptyList(), violations(exchange), state.status.name)
            val body = Json.parseToJsonElement(exchange.response.body).jsonObject
            assertEquals(state.status.name, body.getValue("status").jsonPrimitive.content)
        }
    }

    private fun exchange(api: TestApi, method: String, path: String, body: String? = null): Exchange =
        Exchange(method, path, api.call(method, path, body))

    private fun seededApi(state: TaskState): TestApi {
        val api = TestApi()
        api.tasks.items[TEST_TASK_ID] = taskItem(state = state)
        return api
    }

    private fun brokenApi(): TestApi {
        val tasks = FakeTaskRepository()
        tasks.failure = IllegalStateException("DynamoDB is not reachable")
        return TestApi(tasks)
    }

    private fun violations(exchange: Exchange): List<String> {
        val response = exchange.response
        val contentType = response.headers.getValue("Content-Type")
        return validator.validateResponse(
            exchange.method,
            exchange.path,
            response.statusCode,
            contentType,
            response.body,
        )
    }
}
