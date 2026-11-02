package org.ntust.app.tigerduck.sync.outbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The order every sync sends what waits to go up in today, read from the source as
 * `SyncWiringTest` reads it: Home's full sync and its revision poll (`retryUnsent`) and the
 * background worker each send the six queues one after the other, in one order. The outbox drains
 * its channels in that order, so moving the six onto it sends nothing in another order.
 */
class OutboxOrderTest {

    private fun source(rel: String): String {
        val path = "src/main/java/org/ntust/app/tigerduck/$rel"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, path).let { if (it.isFile) return it.readText() }
            File(dir, "app/$path").let { if (it.isFile) return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $path from ${File("").absolutePath}")
    }

    // Each queue's send of what waits, by the name the syncs hold it under.
    private val retry = Regex("""\b(courseIgnoreSync|unsentMarks|pendingCourseAdds|pendingCourseDeletes|pendingCourseOverrides|pendingHolidayOverrides)\.retry(?:Pending)?\(signIn\)""")

    private fun sendsIn(rel: String): List<String> = retry.findAll(source(rel)).map { it.groupValues[1] }.toList()

    @Test
    fun `Home's sync and the worker send what waits in one order`() {
        val today = listOf(
            "courseIgnoreSync", "unsentMarks", "pendingCourseAdds", "pendingCourseDeletes", "pendingCourseOverrides",
            "pendingHolidayOverrides",
        )
        assertEquals(today, sendsIn("ui/screen/home/HomeBackendSync.kt"))
        assertEquals(today, sendsIn("notification/BackgroundSyncWorker.kt"))
    }
}
