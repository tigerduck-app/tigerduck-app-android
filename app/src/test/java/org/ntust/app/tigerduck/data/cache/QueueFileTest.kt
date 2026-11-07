package org.ntust.app.tigerduck.data.cache

import android.app.Application
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.model.EditClocks
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The six outbox queues' files: their names are persisted, and each queue's file can be named. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QueueFileTest {

    private lateinit var cache: DataCache
    private lateinit var userDataDir: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        userDataDir = File(context.filesDir, "TigerDuckData")
    }

    @Test
    fun `each queue file keeps the name an older build wrote`() {
        assertEquals("pending_course_ignores.json", QueueFile.COURSE_IGNORES.fileName)
        assertEquals("unsent_marks.json", QueueFile.UNSENT_MARKS.fileName)
        assertEquals("pending_course_adds.json", QueueFile.COURSE_ADDS.fileName)
        assertEquals("pending_course_add_times.json", QueueFile.COURSE_ADD_TIMES.fileName)
        assertEquals("pending_course_deletes.json", QueueFile.COURSE_DELETES.fileName)
        assertEquals("pending_course_overrides.json", QueueFile.COURSE_OVERRIDES.fileName)
        assertEquals("pending_course_add_clocks.json", QueueFile.COURSE_ADD_CLOCKS.fileName)
        assertEquals("pending_course_delete_clocks.json", QueueFile.COURSE_DELETE_CLOCKS.fileName)
        assertEquals(8, QueueFile.entries.size)
    }

    @Test
    fun `a queue's file is in the user data folder under its name`() {
        QueueFile.entries.forEach {
            assertEquals(File(userDataDir, it.fileName), cache.queueFile(it))
        }
    }

    @Test
    fun `a save makes the queue's file exist`() = runTest {
        val signIn = testSignIn()
        QueueFile.entries.forEach { assertFalse(it.name, cache.queueFile(it).exists()) }

        cache.savePendingCourseIgnores(
            listOf(PendingCourseIgnore("1141", "CS1001", "mid-1", null, true, 5L)),
            signIn,
        )
        assertTrue(cache.queueFile(QueueFile.COURSE_IGNORES).exists())
        cache.saveUnsentMarks(listOf(UnsentMark("77", "ignored")), signIn)
        assertTrue(cache.queueFile(QueueFile.UNSENT_MARKS).exists())
        cache.savePendingCourseAdds(mapOf("1141" to listOf("CS1001")), signIn)
        assertTrue(cache.queueFile(QueueFile.COURSE_ADDS).exists())
        cache.savePendingCourseAddTimes(mapOf("1141" to mapOf("CS1001" to 9L)), signIn)
        assertTrue(cache.queueFile(QueueFile.COURSE_ADD_TIMES).exists())
        cache.savePendingCourseDeletes(mapOf("1141" to mapOf("CS1001" to 9L)), signIn)
        assertTrue(cache.queueFile(QueueFile.COURSE_DELETES).exists())
        cache.savePendingCourseOverrides(listOf(PendingCourseOverride("mid-1", "#112233", null, null, 3L)), signIn)
        assertTrue(cache.queueFile(QueueFile.COURSE_OVERRIDES).exists())
        for (clocks in listOf(QueueFile.COURSE_ADD_CLOCKS, QueueFile.COURSE_DELETE_CLOCKS)) {
            cache.saveEditClocks(clocks, mapOf(9L to EditClocks(elapsedMs = 5L, boot = 7)), signIn)
            assertTrue(cache.queueFile(clocks).exists())
            assertEquals(mapOf(9L to EditClocks(elapsedMs = 5L, boot = 7)), cache.loadEditClocks(clocks))
        }
    }

    @Test
    fun `a save writes only that queue's file`() = runTest {
        cache.saveUnsentMarks(listOf(UnsentMark("77", "ignored")), testSignIn())

        assertEquals(
            listOf(QueueFile.UNSENT_MARKS.fileName),
            QueueFile.entries.filter { cache.queueFile(it).exists() }.map { it.fileName },
        )
    }

    @Test
    fun `a load reads what the queue's file holds`() = runTest {
        cache.queueFile(QueueFile.COURSE_IGNORES).writeText(
            """[{"semester":"1141","courseNo":"CS1001","moodleId":"mid-1","ignore":true,"changedAtMs":5}]""",
        )
        cache.queueFile(QueueFile.UNSENT_MARKS).writeText("""[{"assignmentId":"77","status":"ignored"}]""")
        cache.queueFile(QueueFile.COURSE_ADDS).writeText("""{"1141":["CS1001"]}""")
        cache.queueFile(QueueFile.COURSE_ADD_TIMES).writeText("""{"1141":{"CS1001":9}}""")
        cache.queueFile(QueueFile.COURSE_DELETES).writeText("""{"1141":{"CS2002":8}}""")
        cache.queueFile(QueueFile.COURSE_OVERRIDES).writeText(
            """[{"moodleId":"mid-1","colorHex":"#112233","changedAtMs":3}]""",
        )

        assertEquals(listOf("mid-1"), cache.loadPendingCourseIgnores().map { it.moodleId })
        assertEquals(listOf("77"), cache.loadUnsentMarks().map { it.assignmentId })
        assertEquals(mapOf("1141" to listOf("CS1001")), cache.loadPendingCourseAdds())
        assertEquals(mapOf("1141" to mapOf("CS1001" to 9L)), cache.loadPendingCourseAddTimes())
        assertEquals(mapOf("1141" to mapOf("CS2002" to 8L)), cache.loadPendingCourseDeletes())
        assertEquals(listOf("#112233"), cache.loadPendingCourseOverrides().map { it.colorHex })
    }
}
