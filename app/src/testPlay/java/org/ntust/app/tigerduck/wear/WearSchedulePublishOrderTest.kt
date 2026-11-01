package org.ntust.app.tigerduck.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.shared.Course

/**
 * The schedule item is put one publish at a time, as [WearScheduleBridge.publish] puts it. A
 * course save, the sign-in, the calendar and the course changes feed each start a publish of
 * their own, and one that read the phone before another must not be the item the watch is left
 * with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WearSchedulePublishOrderTest {

    // Thursdays: 2-3 and 7 in TR-313.
    private val course = Course.fromSchedule(
        courseNo = "CC900",
        courseName = "Fake Course",
        classroom = "TR-313",
        schedule = mapOf(4 to listOf("2", "3", "7")),
    )

    private fun feed(vararg rows: String) = CourseChanges.decode("""{"term":"1151","changes":[${rows.joinToString(",")}]}""")

    @Test
    fun `a publish that read the feed before a newer one is not put after it`() = runTest {
        // Period 7 cancelled, and then the school takes the cancellation back.
        var held = feed("""{"id":1,"course_no":"CC900","kind":"cancelled","periods":["7"],"class_days":["2026-11-05"]}""")
        val encoded = CompletableDeferred<Unit>()
        var snapshots = 0
        val puts = mutableListOf<String>()
        val publisher = WatchItemPublisher(
            snapshot = {
                val sent = wearCourseChanges(listOf(course), held)
                // The course save's publish stops in its encoding, after it read the feed.
                if (snapshots++ == 0) encoded.await()
                sent
            },
        ) { puts += it }

        launch { publisher.publish() }
        runCurrent()
        held = feed()
        // The feed collector's publish.
        launch { publisher.publish() }
        runCurrent()
        encoded.complete(Unit)
        runCurrent()

        assertEquals(wearCourseChanges(listOf(course), feed()), puts.last())
    }

    @Test
    fun `a sign-out's publish is not overtaken by a signed-in one read before it`() = runTest {
        var signedIn = true
        val submitted = CompletableDeferred<Unit>()
        var putsStarted = 0
        val puts = mutableListOf<Pair<Boolean, List<String>>>()
        val publisher = WatchItemPublisher(
            snapshot = { signedIn to if (signedIn) listOf(course.courseNo) else emptyList() },
        ) { item ->
            // The calendar's publish, read while signed in, is slow to be put.
            if (putsStarted++ == 0) submitted.await()
            puts += item
        }

        launch { publisher.publish() }
        runCurrent()
        signedIn = false
        // The sign-out's publish.
        launch { publisher.publish() }
        runCurrent()
        submitted.complete(Unit)
        runCurrent()

        assertEquals(false to emptyList<String>(), puts.last())
    }
}
