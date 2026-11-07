package org.ntust.app.tigerduck.wear.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.AccentGradient
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.AccentGradientStyle
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
        accentGradient: AccentGradient? = null,
    ) = mutablePreferencesOf().also {
        SchedulePersistence.writeSnapshot(
            it, coursesJson, "#112233", accentGradient, syncedAtMs = 1_000L,
            loggedIn = true, languageTag = "zh-Hant-TW", disableScreenCaptureProtection = false,
            quietDays = quietDays,
        )
    }

    @Test
    fun `the interface style the phone sends is stored and read back`() {
        val prefs = write(quietDays = null)
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentGradient = null, syncedAtMs = 2_000L,
            loggedIn = true, languageTag = null, disableScreenCaptureProtection = false, quietDays = null,
            visualPreset = "tat",
        )
        assertEquals("tat", SchedulePersistence.readSnapshot(prefs).visualPreset)
    }

    @Test
    fun `a phone that predates the interface style clears an earlier one`() {
        val prefs = write(quietDays = null)
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentGradient = null, syncedAtMs = 2_000L,
            loggedIn = true, languageTag = null, disableScreenCaptureProtection = false, quietDays = null,
            visualPreset = "tat",
        )
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentGradient = null, syncedAtMs = 3_000L,
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
            prefs, coursesJson, "#112233", accentGradient = null,
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
        assertNull(snapshot.accentGradient)
    }

    private val gradient = requireNotNull(
        AccentGradient.of(listOf(0x112233, 0xAF52DE, 0x34C759), AccentGradientStyle.Mesh),
    )

    @Test
    fun `the gradient the phone sends is stored and read back, in every style`() {
        for (style in AccentGradientStyle.entries) {
            val sent = gradient.withStyle(style).withDirection(AccentGradientDirection.BottomToTop)
            val snapshot = SchedulePersistence.readSnapshot(write(null, sent))
            assertEquals("#112233", snapshot.accentHex)
            assertEquals(sent, snapshot.accentGradient)
        }
    }

    @Test
    fun `a single color the phone sends next clears the gradient`() {
        val prefs = write(null, gradient)
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentGradient = null,
            syncedAtMs = 2_000L, loggedIn = true, languageTag = null,
            disableScreenCaptureProtection = false, quietDays = null,
        )
        assertNull(SchedulePersistence.readSnapshot(prefs).accentGradient)
    }

    /**
     * Upgrade guard: the release before stored a gradient as its end and direction, by these
     * on-disk names. It draws as two stops of a linear gradient until the phone's next publish.
     */
    @Test
    fun `the end and direction an older watch build stored read as a two-stop linear gradient`() {
        val stored = preferencesOf(
            stringPreferencesKey("courses_json") to coursesJson,
            stringPreferencesKey("accent_hex") to "#112233",
            stringPreferencesKey("accent_end_hex") to "#AF52DE",
            intPreferencesKey("accent_direction") to 6,
        )
        assertEquals(
            AccentGradient.of(listOf(0x112233, 0xAF52DE), AccentGradientStyle.Linear, AccentGradientDirection.BottomLeftToTopRight),
            SchedulePersistence.readSnapshot(stored).accentGradient,
        )
        val noDirection = preferencesOf(
            stringPreferencesKey("accent_hex") to "#112233",
            stringPreferencesKey("accent_end_hex") to "#AF52DE",
        )
        assertEquals(AccentGradientDirection.TopLeftToBottomRight, SchedulePersistence.readSnapshot(noDirection).accentGradient?.direction)
    }

    @Test
    fun `the next payload replaces what an older watch build stored`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("accent_hex") to "#112233",
            stringPreferencesKey("accent_end_hex") to "#AF52DE",
            intPreferencesKey("accent_direction") to 6,
        )
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentGradient = null,
            syncedAtMs = 2_000L, loggedIn = true, languageTag = null,
            disableScreenCaptureProtection = false, quietDays = null,
        )
        assertNull(SchedulePersistence.readSnapshot(prefs).accentGradient)
    }

    @Test
    fun `a stored gradient this build cannot read is none, and the schedule still loads`() {
        val stored = preferencesOf(
            stringPreferencesKey("courses_json") to coursesJson,
            stringPreferencesKey("accent_hex") to "#112233",
            stringPreferencesKey("accent_gradient") to """{"stops":[1,2],"style":"spiral","direction":"leftToRight"}""",
            stringPreferencesKey("accent_end_hex") to "#AF52DE",
        )
        val snapshot = SchedulePersistence.readSnapshot(stored)
        assertNull(snapshot.accentGradient)
        assertEquals("CS101", snapshot.courses.single().courseNo)
        assertEquals("#112233", snapshot.accentHex)
    }

    // ---- the school's course changes ----

    /** CS101's Monday 2-3 moved to TR-411 on 2026-10-12, as the phone sends it. */
    private val courseChangesJson =
        """{"CS101":{"2026-10-12":[{"periods":["2","3"],"cancelled":false,"room":"TR-411"}]}}"""

    private fun writeChanges(prefs: MutablePreferences, courseChanges: String?) =
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", accentGradient = null, syncedAtMs = 2_000L,
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
