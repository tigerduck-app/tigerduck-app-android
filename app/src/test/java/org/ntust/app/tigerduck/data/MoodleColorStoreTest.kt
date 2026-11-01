package org.ntust.app.tigerduck.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.JvmContext
import java.io.File

/**
 * Which courses take Moodle's default color, over a real file: a course already colored when the
 * file did not exist keeps its color, a new course takes Moodle's, and a sign-out forgets both
 * (tigerduck-app/tigerduck-app-android#638).
 */
class MoodleColorStoreTest {

    private lateinit var context: JvmContext
    private lateinit var cache: DataCache
    private lateinit var file: File

    private fun course(no: String) = Course(courseNo = no, courseName = no, moodleIdNumber = "1151$no")

    private fun store(book: MoodleColorBook = MoodleColorBook()) = MoodleColorStore(file, cache, book)

    @Before
    fun setUp() {
        context = JvmContext()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        file = File(context.noBackupFilesDir, MoodleColorStore.FILE_NAME)
    }

    @Test
    fun `an upgrade keeps the courses already cached and colors the next one from Moodle`() = runBlocking {
        cache.saveCourses(listOf(course("CS1")), "1151")
        cache.saveMoodleCourseIds(mapOf("1151CS1" to 4127, "1151CS2" to 5337))
        val book = MoodleColorBook()

        store(book).ensureLoaded()
        book.reconcile(listOf(course("CS1"), course("CS2")))

        assertEquals(mapOf("CS2" to MoodleColorBook.forCourseId(5337)), book.colors())
    }

    @Test
    fun `a color given is written, and read back by the next process`() = runBlocking {
        cache.saveMoodleCourseIds(mapOf("1151CS1" to 4127))
        val first = MoodleColorBook()
        store(first).ensureLoaded()
        first.reconcile(listOf(course("CS1")))
        repeat(50) { if (!file.exists()) Thread.sleep(20) }
        assertTrue(file.exists())

        val second = MoodleColorBook()
        store(second).ensureLoaded()

        assertEquals(mapOf("CS1" to MoodleColorBook.forCourseId(4127)), second.colors())
    }

    @Test
    fun `a sign-out deletes the file and leaves the next account's courses new`() = runBlocking {
        cache.saveMoodleCourseIds(mapOf("1151CS1" to 4127))
        val book = MoodleColorBook()
        val store = store(book)
        store.ensureLoaded()
        book.reconcile(listOf(course("CS1")))
        repeat(50) { if (!file.exists()) Thread.sleep(20) }

        store.forgetAccount()

        assertFalse(file.exists())
        assertTrue(book.colors().isEmpty())
        book.setIds(mapOf("1151CS1" to 4127))
        assertTrue(book.reconcile(listOf(course("CS1"))))
    }
}
