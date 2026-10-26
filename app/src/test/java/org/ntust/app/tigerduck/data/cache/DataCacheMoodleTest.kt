package org.ntust.app.tigerduck.data.cache

import android.app.Application
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The per-account Moodle copies the course screens draw from. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DataCacheMoodleTest {

    private lateinit var context: Application
    private lateinit var cache: DataCache
    private val type = object : TypeToken<MoodleCourseGrades>() {}.type

    private val grades = MoodleCourseGrades(
        listOf(MoodleGradeItem(1, "HW1", "mod", "assign", 31, "95", 100.0, false, "Nice")),
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
    }

    @Test
    fun `a copy comes back with the time it was fetched, for its own account only`() = runTest {
        cache.saveMoodleData(grades, 1234L, "courses/5/grades", "B11300001")

        val loaded = cache.loadMoodleData<MoodleCourseGrades>(type, "courses/5/grades", "B11300001")
        assertEquals(grades, loaded!!.value)
        assertEquals(1234L, loaded.fetchedAtMs)
        assertNull(cache.loadMoodleData<MoodleCourseGrades>(type, "courses/5/grades", "B11300002"))
    }

    @Test
    fun `a copy in another shape reads as absent`() = runTest {
        cache.saveMoodleData(grades, 1L, "courses/5/grades", "B11300001")
        val file = File(cache.moodleFilesDir("B11300001"), "courses/5/grades.json")
        file.writeText(file.readText().replace("\"v\":${DataCache.MOODLE_CACHE_SCHEMA}", "\"v\":0"))

        assertNull(cache.loadMoodleData<MoodleCourseGrades>(type, "courses/5/grades", "B11300001"))
    }

    @Test
    fun `sign-out wipes every account's copies, pictures and downloaded files`() = runTest {
        cache.saveMoodleData(grades, 1L, "courses/5/grades", "B11300001")
        val picture = File(cache.moodleFilesDir("B11300001"), "pictures/1-3").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        val downloaded = File(cache.moodleDownloadsDir("B11300001"), "90/p1/ab/1/hw3.pdf").apply {
            parentFile!!.mkdirs()
            writeText("pdf")
        }

        cache.clearAllUserData()

        assertNull(cache.loadMoodleData<MoodleCourseGrades>(type, "courses/5/grades", "B11300001"))
        assertFalse(picture.exists())
        assertFalse("downloaded files go too", downloaded.exists())
    }

    @Test
    fun `a name cannot climb out of the account's folder`() = runTest {
        cache.saveMoodleData(grades, 1L, "../../escape", "B11300001")

        assertFalse(File(context.cacheDir, "escape.json").exists())
        assertEquals(grades, cache.loadMoodleData<MoodleCourseGrades>(type, "escape", "B11300001")!!.value)
    }

    @Test
    fun `the downloaded files' folder does not name the account, nor hash it where anyone could redo it`() {
        val dir = cache.moodleDownloadsDir("B11300001")
        assertFalse(dir.path.contains("B11300001", ignoreCase = true))
        val plainHash = java.security.MessageDigest.getInstance("SHA-256").digest("B11300001".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        assertFalse("salted per install", dir.name == plainHash)
        assertEquals(dir, cache.moodleDownloadsDir("B11300001"))
        assertEquals(
            "the same on this install after a restart",
            dir,
            DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context))).moodleDownloadsDir("B11300001"),
        )
        assertFalse(dir == cache.moodleDownloadsDir("B11300002"))
    }

    @Test
    fun `the folder salt is kept for the process, even when it never reached the disk`() {
        val dir = cache.moodleDownloadsDir("B11300001")
        // As if the write had failed: nothing on disk.
        context.getSharedPreferences("moodle_files", android.content.Context.MODE_PRIVATE).edit().clear().commit()

        assertEquals(dir, cache.moodleDownloadsDir("B11300001"))
    }

    @Test
    fun `a full reset makes a new folder salt`() = runTest {
        val before = cache.moodleDownloadsDir("B11300001")

        cache.clearEverything()

        val after = cache.moodleDownloadsDir("B11300001")
        assertFalse("a new salt names the folder anew", before == after)
        assertEquals(
            "and the new one is the one kept",
            after,
            DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context))).moodleDownloadsDir("B11300001"),
        )
    }

    private fun record(id: Int) = MoodleAssignmentRecord(
        assignId = id, cmid = id * 10, courseId = 5, name = "HW $id", dueDate = 1_790_600_000L, cutoffDate = 0,
        allowSubmissionsFrom = 0, intro = "<p>Do it</p>",
        introAttachments = listOf(
            MoodleFile("spec.pdf", 10, "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_assign/introattachment/0/spec.pdf", "application/pdf", 1L, "/"),
        ),
        noSubmissions = false, teamSubmission = true,
    )

    @Test
    fun `the assignment records come back whole, and go with the account's data`() = runTest {
        val records = listOf(record(701), record(702))

        assertTrue(cache.saveAssignmentRecords(records))

        assertEquals(records, cache.loadAssignmentRecords())
        assertEquals(1, cache.assignmentRecordsVersion.value)
        cache.clearAllUserData()
        assertTrue(cache.loadAssignmentRecords().isEmpty())
    }

    @Test
    fun `records from a round the account signed out of are not kept`() = runTest {
        val generation = cache.userDataGeneration
        cache.clearAllUserData()

        assertFalse(cache.saveAssignmentRecords(listOf(record(701)), generation))

        assertTrue(cache.loadAssignmentRecords().isEmpty())
        assertEquals(0, cache.assignmentRecordsVersion.value)
    }

    @Test
    fun `records in another shape read as none`() = runTest {
        cache.saveAssignmentRecords(listOf(record(701)))
        val file = File(context.cacheDir, "TigerDuckCache/moodle_assignments.json")
        file.writeText(file.readText().replace("\"v\":${DataCache.MOODLE_CACHE_SCHEMA}", "\"v\":0"))

        assertTrue(cache.loadAssignmentRecords().isEmpty())
    }

    @Test
    fun `every write of the assignments is announced`() = runTest {
        val before = cache.assignmentsVersion.value

        cache.saveAssignments(emptyList())
        cache.updateAssignments { it }

        assertEquals(before + 2, cache.assignmentsVersion.value)
    }

    @Test
    fun `downloaded files are kept where the system does not clear them for space, and a full reset deletes them`() = runTest {
        val file = File(cache.moodleDownloadsDir("B11300001"), "5/m1/ab/1/syllabus.pdf").apply {
            parentFile!!.mkdirs()
            writeText("pdf")
        }
        assertTrue(file.path.startsWith(context.filesDir.path))

        cache.clearEverything()

        assertFalse(file.exists())
        assertFalse(cache.moodleDownloadsRoot().exists())
    }
}
