package imageprocessor.api.tasks

import imageprocessor.api.http.ApiResponse
import imageprocessor.api.http.errorResponse
import imageprocessor.api.http.jsonResponse
import imageprocessor.api.signing.LinkSigner
import imageprocessor.shared.contract.models.ErrorCode
import imageprocessor.shared.contract.models.Task
import imageprocessor.shared.store.TaskRepository
import java.time.Clock

private val TASK_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

class TaskReader(
    private val tasks: TaskRepository,
    private val links: LinkSigner,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun read(requestedId: String): ApiResponse {
        // The spec's `format: uuid` also allows upper case; the stored ids are lower case.
        val id = requestedId.lowercase()
        if (!TASK_ID.matches(id)) {
            return errorResponse(ErrorCode.INVALID_REQUEST, "The identifier is not valid.")
        }
        val item = tasks.get(id)
        if (item == null || item.isExpired(clock.instant())) {
            return errorResponse(ErrorCode.TASK_NOT_FOUND, "The task does not exist.")
        }
        return jsonResponse(200, Task.serializer(), toView(item, links))
    }
}
