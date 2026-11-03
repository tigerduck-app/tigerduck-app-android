package org.ntust.app.tigerduck.sync.outbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The order every sync sends what waits to go up in today, read from the source as
 * `SyncWiringTest` reads it: Home's full sync and its revision poll and the background worker's
 * pass each send the six queues through `UnsentQueues.retry`, one after the other, in one order,
 * [OutboxChannelId]'s. The outbox drains its channels in that order, so moving the six onto it
 * sends nothing in another order. Until `Outbox.drain` takes their place (T3.23).
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

    // The queue each channel is, by the name the syncs hold it under.
    private val queueOf = mapOf(
        OutboxChannelId.COURSE_IGNORES to "courseIgnoreSync",
        OutboxChannelId.MARKS to "unsentMarks",
        OutboxChannelId.COURSE_ADDS to "pendingCourseAdds",
        OutboxChannelId.COURSE_DELETES to "pendingCourseDeletes",
        OutboxChannelId.COURSE_OVERRIDES to "pendingCourseOverrides",
        OutboxChannelId.HOLIDAYS to "pendingHolidayOverrides",
    )

    @Test
    fun `Home's sync and the worker send what waits in the outbox's order`() {
        val today = OutboxChannelId.entries.map { queueOf.getValue(it) }
        assertEquals(today, sendsIn("sync/ForegroundSteps.kt"))
        assertEquals(emptyList<String>(), sendsIn("sync/BackgroundSteps.kt"))
        assertEquals(emptyList<String>(), sendsIn("notification/BackgroundSyncWorker.kt"))
    }
}
