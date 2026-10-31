package org.ntust.app.tigerduck.wear.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.WatchClassChange
import org.ntust.app.tigerduck.shared.WatchCourseChanges
import java.time.LocalDate

class SchedulePersistenceTest {

    /** One course as the phone's `WearScheduleBridge.CourseDto` sends it. */
    private val coursesJson = """
        [{"courseNo":"CS101","courseName":"Algorithms","instructor":"Ada","credits":3,
          "creditsExact":3.0,"classroom":"T3-101","scheduleJson":"{\"1\":[\"2\",\"3\"]}",
          "classroomMapJson":"{}","moodleIdNumber":"1141CS101","customColorHex":null,
          "isManual":false}]
    """.trimIndent()

    private fun write(
        quietDays: List<String>?,
        accentEndHex: String? = null,
        accentDirectionCode: Int? = null,
    ) = mutablePreferencesOf().also {
        SchedulePersistence.writeSnapshot(
            it, coursesJson, "#112233", accentEndHex, accentDirectionCode, syncedAtMs = 1_000L,
            loggedIn = true, languageTag = "zh-Hant-TW", disableScreenCaptureProtection = false,
            quietDays = quietDays,
        )
    }

    @Test
    fun `the interface style the phone sends is stored and read back`() {
        val prefs = write(quietDays = null)
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentEndHex = null, accentDirectionCode = null, syncedAtMs = 2_000L,
            loggedIn = true, languageTag = null, disableScreenCaptureProtection = false, quietDays = null,
            visualPreset = "tat",
        )
        assertEquals("tat", SchedulePersistence.readSnapshot(prefs).visualPreset)
    }

    @Test
    fun `a phone that predates the interface style clears an earlier one`() {
        val prefs = write(quietDays = null)
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentEndHex = null, accentDirectionCode = null, syncedAtMs = 2_000L,
            loggedIn = true, languageTag = null, disableScreenCaptureProtection = false, quietDays = null,
            visualPreset = "tat",
        )
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentEndHex = null, accentDirectionCode = null, syncedAtMs = 3_000L,
            loggedIn = true, languageTag = null, disableScreenCaptureProtection = false, quietDays = null,
            visualPreset = null,
        )
        assertNull(SchedulePersistence.readSnapshot(prefs).visualPreset)
    }

    @Test
    fun `a snapshot an older watch build stored has no interface style`() {
        val stored = preferencesOf(stringPreferencesKey("courses_json") to coursesJson, stringPreferencesKey("accent_hex") to "#112233")
        assertNull(SchedulePersistence.readSnapshot(stored).visualPreset)
    }

    @Test
    fun `the quiet days the phone sends are stored and read back`() {
        val snapshot = SchedulePersistence.readSnapshot(write(listOf("2026-10-09", "2026-10-10")))
        assertEquals(setOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10)), snapshot.quietDays)
        assertEquals("CS101", snapshot.courses.single().courseNo)
    }

    @Test
    fun `a payload from a phone that predates quiet days clears the earlier ones`() {
        val prefs = write(listOf("2026-10-09"))
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentEndHex = null, accentDirectionCode = null,
            syncedAtMs = 2_000L, loggedIn = true, languageTag = null,
            disableScreenCaptureProtection = false, quietDays = null,
        )
        val snapshot = SchedulePersistence.readSnapshot(prefs)
        assertTrue(snapshot.quietDays.isEmpty())
        assertEquals("CS101", snapshot.courses.single().courseNo)
    }

    /**
     * Upgrade guard: the keys a watch build before quiet days stored, by their
     * on-disk names. The snapshot must load as before, with no quiet days,
     * until the phone's next publish.
     */
    @Test
    fun `a snapshot stored by an older watch build loads with no quiet days`() {
        val stored = preferencesOf(
            stringPreferencesKey("courses_json") to coursesJson,
            stringPreferencesKey("accent_hex") to "#112233",
            longPreferencesKey("synced_at_ms") to 1_000L,
            booleanPreferencesKey("logged_in") to true,
            stringPreferencesKey("language_tag") to "zh-Hant-TW",
            booleanPreferencesKey("disable_screen_capture_protection") to false,
        )
        val snapshot = SchedulePersistence.readSnapshot(stored)
        assertEquals("CS101", snapshot.courses.single().courseNo)
        assertEquals("#112233", snapshot.accentHex)
        assertEquals(1_000L, snapshot.syncedAtMs)
        assertEquals(true, snapshot.loggedIn)
        assertEquals("zh-Hant-TW", snapshot.languageTag)
        assertTrue(snapshot.quietDays.isEmpty())
        // Nor a gradient: the accent draws alone until the phone's next publish.
        assertNull(snapshot.accentEndHex)
        assertNull(snapshot.accentDirection)
    }

    @Test
    fun `the gradient the phone sends is stored and read back`() {
        val snapshot = SchedulePersistence.readSnapshot(write(null, "#AF52DE", 2))
        assertEquals("#112233", snapshot.accentHex)
        assertEquals("#AF52DE", snapshot.accentEndHex)
        assertEquals(AccentGradientDirection.LeftToRight, snapshot.accentDirection)
    }

    @Test
    fun `the directions from a bottom corner are read back from their codes`() {
        assertEquals(
            AccentGradientDirection.BottomLeftToTopRight,
            SchedulePersistence.readSnapshot(write(null, "#AF52DE", 6)).accentDirection,
        )
        assertEquals(
            AccentGradientDirection.BottomRightToTopLeft,
            SchedulePersistence.readSnapshot(write(null, "#AF52DE", 7)).accentDirection,
        )
    }

    @Test
    fun `a single color the phone sends next clears the gradient`() {
        val prefs = write(null, "#AF52DE", 2)
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentEndHex = null, accentDirectionCode = null,
            syncedAtMs = 2_000L, loggedIn = true, languageTag = null,
            disableScreenCaptureProtection = false, quietDays = null,
        )
        val snapshot = SchedulePersistence.readSnapshot(prefs)
        assertNull(snapshot.accentEndHex)
        assertNull(snapshot.accentDirection)
    }

    @Test
    fun `a gradient without a direction, or with one this build does not know, runs top left to bottom right`() {
        assertEquals(
            AccentGradientDirection.TopLeftToBottomRight,
            SchedulePersistence.readSnapshot(write(null, "#AF52DE", null)).accentDirection,
        )
        assertEquals(
            AccentGradientDirection.TopLeftToBottomRight,
            SchedulePersistence.readSnapshot(write(null, "#AF52DE", 99)).accentDirection,
        )
    }

    @Test
    fun `a direction sent without an end is no gradient`() {
        val snapshot = SchedulePersistence.readSnapshot(write(null, null, 2))
        assertNull(snapshot.accentEndHex)
        assertNull(snapshot.accentDirection)
    }

    // ---- the school's course changes ----

    /** CS101's Monday 2-3 moved to TR-411 on 2026-10-12, as the phone sends it. */
    private val courseChangesJson =
        """{"CS101":{"2026-10-12":[{"periods":["2","3"],"cancelled":false,"room":"TR-411"}]}}"""

    private fun writeChanges(prefs: MutablePreferences, courseChanges: String?) =
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentEndHex = null, accentDirectionCode = null, syncedAtMs = 2_000L,
            loggedIn = true, languageTag = null, disableScreenCaptureProtection = false, quietDays = null,
            courseChanges = courseChanges,
        )

    @Test
    fun `the course changes the phone sends are stored and read back`() {
        val prefs = mutablePreferencesOf().also { writeChanges(it, courseChangesJson) }
        val snapshot = SchedulePersistence.readSnapshot(prefs)
        val monday = LocalDate.of(2026, 10, 12)
        assertEquals(
            WatchCourseChanges(mapOf("CS101" to mapOf(monday to listOf(WatchClassChange(listOf("2", "3"), room = "TR-411"))))),
            snapshot.courseChanges,
        )
        assertEquals("TR-411", snapshot.todaysClasses(monday, 8 * 60).single().classroom)
        assertEquals("T3-101", snapshot.todaysClasses(monday.plusDays(7), 8 * 60).single().classroom)
    }

    @Test
    fun `a payload from a phone that predates course changes clears the earlier ones`() {
        val prefs = mutablePreferencesOf().also { writeChanges(it, courseChangesJson) }
        writeChanges(prefs, null)
        val snapshot = SchedulePersistence.readSnapshot(prefs)
        assertEquals(WatchCourseChanges.EMPTY, snapshot.courseChanges)
        assertEquals("CS101", snapshot.courses.single().courseNo)
    }

    /** Upgrade guard: a watch build before course changes stored no such key. */
    @Test
    fun `a snapshot an older watch build stored has no course changes`() {
        val stored = preferencesOf(
            stringPreferencesKey("courses_json") to coursesJson,
            stringPreferencesKey("accent_hex") to "#112233",
            longPreferencesKey("synced_at_ms") to 1_000L,
        )
        val snapshot = SchedulePersistence.readSnapshot(stored)
        assertEquals(WatchCourseChanges.EMPTY, snapshot.courseChanges)
        assertEquals("CS101", snapshot.courses.single().courseNo)
    }

    @Test
    fun `malformed course changes read as none and the schedule still loads`() {
        for (raw in listOf("", "{\"CS101\":", "[1,2]", """{"CS101":{"2026-10-12":"moved"}}""")) {
            val snapshot = SchedulePersistence.readSnapshot(mutablePreferencesOf().also { writeChanges(it, raw) })
            assertEquals(raw, WatchCourseChanges.EMPTY, snapshot.courseChanges)
            assertEquals(raw, "CS101", snapshot.courses.single().courseNo)
        }
    }
}
