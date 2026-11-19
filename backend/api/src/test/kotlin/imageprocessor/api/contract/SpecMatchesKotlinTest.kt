package imageprocessor.api.contract

import imageprocessor.shared.tasks.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecMatchesKotlinTest {
    private val reader = SpecReader(specPath())

    @Test
    fun `the statuses of Task in the spec equal TaskStatus`() {
        assertEquals(reader.taskStatuses(), TaskStatus.entries.map { status -> status.name }.toSet())
    }
}
