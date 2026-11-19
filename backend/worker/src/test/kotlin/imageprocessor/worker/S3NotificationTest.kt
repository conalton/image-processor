package imageprocessor.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class S3NotificationTest {
    private fun taskIds(body: String): List<String> = decodeS3Notification(body).taskIds("originals/")

    private fun created(key: String): String =
        """{"Records":[{"eventName":"ObjectCreated:Put","s3":{"bucket":{"name":"images"},"object":{"key":"$key","size":3}}}]}"""

    @Test
    fun `a test event names no task, any other JSON that is not a notification is refused`() {
        val testEvent =
            """{"Service":"Amazon S3","Event":"s3:TestEvent","Time":"2026-10-20T12:00:00.000Z","Bucket":"images"}"""

        assertTrue(taskIds(testEvent).isEmpty())
        assertFailsWith<IllegalArgumentException> { decodeS3Notification("{}") }
        assertFailsWith<IllegalArgumentException> { decodeS3Notification("""{"Type":"Notification","Message":"{}"}""") }
    }

    @Test
    fun `a key outside the originals prefix is ignored`() {
        assertTrue(taskIds(created("derived/0b8f2c5e/medium.jpg")).isEmpty())
        assertTrue(taskIds(created("0b8f2c5e")).isEmpty())
        assertTrue(taskIds(created("originals/")).isEmpty())
        assertTrue(taskIds(created("originals/0b8f2c5e/extra")).isEmpty())
    }

    @Test
    fun `the id is the URL-decoded key without the prefix`() {
        assertEquals(listOf("0b8f2c5e"), taskIds(created("originals/0b8f2c5e")))
        assertEquals(listOf("a b+c"), taskIds(created("originals/a+b%2Bc")))
    }
}
