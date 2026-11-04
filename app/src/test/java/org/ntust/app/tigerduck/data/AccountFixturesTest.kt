package org.ntust.app.tigerduck.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.data.model.UnsentMark
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The files and preference keys a build of today writes for the six queues and the school's
 * sync preferences, kept as fixtures under `src/test/resources/account/`. A later task that moves
 * the writers reads these files in place of the writers' output, and must find them unchanged:
 * the update leaves queued edits to be read and sent, with no sign-out and no migration.
 *
 * This test builds them with this build's own writers and compares. A mismatch means the shape
 * on disk changed, which an upgrade would not survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountFixturesTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/account/$name")) { "missing fixture $name" }.readText().trimEnd()

    @Test
    fun `the six queues' files are what the fixtures hold`() = runBlocking {
        val prefs = AppPreferences(context)
        val cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
        assertTrue(cache.savePendingCourseAdds(mapOf("1141" to listOf("CS1001", "CS1002")), testSignIn()))
        assertTrue(cache.savePendingCourseAddTimes(mapOf("1141" to mapOf("CS1001" to 1_700_000_000_000L)), testSignIn()))
        assertTrue(cache.savePendingCourseDeletes(mapOf("1141" to mapOf("CS1003" to 1_700_000_001_000L)), testSignIn()))
        assertTrue(
            cache.savePendingCourseOverrides(
                listOf(PendingCourseOverride("1141CS1001", "#336699", null, null, 1_700_000_002_000L)),
                testSignIn(),
            ),
        )
        assertTrue(cache.saveUnsentMarks(listOf(UnsentMark("7", "ignored", 1, 1_700_000_003_000L, 1_700_000_004_000L)), testSignIn()))
        assertTrue(cache.savePendingCourseIgnores(listOf(PendingCourseIgnore("1141", "CS1001", "1141CS1001", 42, true, 1_700_000_005_000L)), testSignIn()))

        val dir = File(context.filesDir, "TigerDuckData")
        // Every file is compared before one fails, so a changed shape shows in full.
        val written = QUEUE_FILES.joinToString("\n") { name -> "$name: " + File(dir, name).readText().trimEnd() }
        val expected = QUEUE_FILES.joinToString("\n") { name -> "$name: " + fixture("queues/$name") }
        assertEquals(expected, written)
    }

    @Test
    fun `the school and holiday preference keys are what the fixture holds`() {
        val prefs = AppPreferences(context)
        prefs.school.assignmentMarksSyncedOnce.set(true, testSignIn())
        prefs.school.assignmentsUploadFingerprint.set("fingerprint-1", testSignIn())
        prefs.school.assignmentsUploadedAtMs.set(1_700_000_000_000L, testSignIn())
        prefs.school.backgroundCoursesSyncedAtMs.set(1_700_000_001_000L, testSignIn())
        prefs.school.backgroundCoursesTerm.set("1141", testSignIn())
        prefs.school.backgroundOverridesSyncedAtMs.set(1_700_000_002_000L, testSignIn())
        prefs.school.classTableSelectedSemester.set("1141", testSignIn())
        prefs.school.emptyClassroomBuilding.set("TR", testSignIn())
        // What an older build kept of the server's last course reset, which nothing reads any more.
        context.getSharedPreferences("tigerduck_prefs", 0).edit().putString("lastSeenCoursesResetAt", "2026-09-01T00:00:00Z").commit()
        prefs.school.pendingConflictCategories.set(setOf("courses"), testSignIn())
        prefs.school.schoolDataSyncedAtMs.set(1_700_000_003_000L, testSignIn())
        prefs.school.ssoLoginTimestamp.set(1_700_000_004_000L, testSignIn())
        prefs.school.syncedCategories.set(setOf("assignments", "courses"), testSignIn())
        prefs.holidayNotifyOverrides = setOf(3, 5)
        prefs.holidayOverridesAwaitingUpload = setOf(5)

        val all = context.getSharedPreferences("tigerduck_prefs", 0).all
        val written = SCHOOL_KEYS.joinToString("\n") { key ->
            val value = all[key]
            val shown = if (value is Set<*>) value.map { it.toString() }.sorted().toString() else value.toString()
            "$key ${value?.javaClass?.simpleName} $shown"
        }
        assertEquals(fixture("school-prefs.txt"), written)
    }

    private companion object {
        val QUEUE_FILES = listOf(
            "pending_course_adds.json",
            "pending_course_add_times.json",
            "pending_course_deletes.json",
            "pending_course_overrides.json",
            "unsent_marks.json",
            "pending_course_ignores.json",
        )

        val SCHOOL_KEYS = listOf(
            "assignmentMarksSyncedOnce", "assignmentsUploadFingerprint", "assignmentsUploadedAtMs",
            "backgroundCoursesSyncedAtMs", "backgroundCoursesTerm", "backgroundOverridesSyncedAtMs",
            "classTableSelectedSemester", "emptyClassroomBuilding", "lastSeenCoursesResetAt",
            "pendingConflictCategories", "schoolDataSyncedAtMs", "ssoLoginTimestamp", "syncedCategories",
            "holidayNotifyOverrides", "holidayOverridesAwaitingUpload",
        )
    }
}
