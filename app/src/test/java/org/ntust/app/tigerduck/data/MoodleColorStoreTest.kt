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
    private var student: String? = "B00015999"

    private fun course(no: String) = Course(courseNo = no, courseName = no, moodleIdNumber = "1151$no")

    private fun store(book: MoodleColorBook = MoodleColorBook()) = MoodleColorStore(file, cache, book) { student }

    private fun waitForFile() = repeat(100) { if (!file.exists()) Thread.sleep(20) }

    @Before
    fun setUp() {
        context = JvmContext()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        file = File(context.noBackupFilesDir, MoodleColorStore.FILE_NAME)
    }

    @Test
    fun `an upgrade with a hand-added course cached still keeps what the first refresh brings`() = runBlocking {
        cache.saveCourses(listOf(course("CS9").copy(isManual = true)), "1151")
        cache.saveMoodleCourseIds(mapOf("1151CS1" to 4127, "1151CS2" to 5337))
        val book = MoodleColorBook()

        store(book).ensureLoaded()
        book.reconcile(listOf(course("CS1"), course("CS2")))

        assertTrue(book.colorsFor(listOf(course("CS1"), course("CS2"))).isEmpty())
    }

    @Test
    fun `an upgrade with the cache evicted keeps what the first refresh brings`() = runBlocking {
        cache.saveMoodleCourseIds(mapOf("1151CS1" to 4127, "1151CS2" to 5337))
        val book = MoodleColorBook()

        store(book).ensureLoaded()
        book.reconcile(listOf(course("CS1")))

        assertTrue(book.colorsFor(listOf(course("CS1"))).isEmpty())
    }

    @Test
    fun `a color given is written, and read back by the next process`() = runBlocking {
        cache.saveMoodleCourseIds(mapOf("1151CS1" to 4127, "1151CS2" to 5337))
        val first = MoodleColorBook()
        store(first).ensureLoaded()
        first.reconcile(listOf(course("CS1")))
        // The upgrade's refresh is absorbed; a later course is new.
        first.reconcile(listOf(course("CS1"), course("CS2")))
        waitForFile()
        assertTrue(file.exists())

        val second = MoodleColorBook()
        store(second).ensureLoaded()

        assertEquals(
            mapOf("CS2" to MoodleColorBook.forCourseId(5337)),
            second.colorsFor(listOf(course("CS1"), course("CS2"))),
        )
    }

    @Test
    fun `a sign-out deletes the file and leaves the next account's courses new`() = runBlocking {
        cache.saveMoodleCourseIds(mapOf("1151CS1" to 4127))
        val book = MoodleColorBook()
        val store = store(book)
        store.ensureLoaded()
        book.reconcile(listOf(course("CS1")))
        waitForFile()

        store.forgetAccount()

        assertFalse(file.exists())
        assertTrue(book.colorsFor(listOf(course("CS1"))).isEmpty())
        book.setIds(mapOf("1151CS1" to 4127))
        assertTrue(book.reconcile(listOf(course("CS1"))))
    }

    @Test
    fun `a sign-out before the first load still leaves the next session persisted`() = runBlocking {
        cache.saveMoodleCourseIds(mapOf("1151CS1" to 4127))
        val book = MoodleColorBook()
        val store = store(book)
        store.forgetAccount()

        store.ensureLoaded()
        book.setIds(mapOf("1151CS1" to 4127))
        book.reconcile(listOf(course("CS1")))
        waitForFile()

        assertTrue(file.exists())
    }

    @Test
    fun `a file left by another student, or by a sign-out cut short, is not inherited`() = runBlocking {
        cache.saveMoodleCourseIds(mapOf("1151CS1" to 4127))
        val first = MoodleColorBook()
        store(first).ensureLoaded()
        first.reconcile(listOf(course("CS1")))
        first.reconcile(listOf(course("CS1"), course("CS2")))
        waitForFile()

        student = "B00015998"
        val second = MoodleColorBook()
        store(second).ensureLoaded()
        second.setIds(mapOf("1151CS1" to 4127))

        // The new student's courses are all new, not the old student's.
        assertTrue(second.reconcile(listOf(course("CS1"))))
    }

    @Test
    fun `a file found while signed out is deleted`() = runBlocking {
        cache.saveMoodleCourseIds(mapOf("1151CS1" to 4127))
        val first = MoodleColorBook()
        store(first).ensureLoaded()
        first.reconcile(listOf(course("CS1")))
        waitForFile()

        student = null
        store(MoodleColorBook()).ensureLoaded()

        assertFalse(file.exists())
    }
}
