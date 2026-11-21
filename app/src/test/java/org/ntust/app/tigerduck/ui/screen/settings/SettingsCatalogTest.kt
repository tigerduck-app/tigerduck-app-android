package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings' catalog (tigerduck-app#939): which rows each page shows, which a sign-in gates, and
 * which pages have nothing to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsCatalogTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Each page's rows as shown, each with what its toast says, null for one that takes input. */
    private fun page(page: PageSettings, state: SettingsState): Map<SettingsRow, Int?> =
        page.rows.filter { it.isShown(state) }.associateWith { it.closedReason(state) }

    @Test
    fun `every row has a title and every page a row`() {
        SettingsRow.entries.forEach { assertTrue("$it has no title", context.getString(it.title).isNotBlank()) }
        PageSettings.entries.forEach { assertTrue("$it lists no row", it.rows.isNotEmpty()) }
    }

    @Test
    fun `each page shows its rows, gated by the sign-ins and never by TigerSync`() {
        val phone = SettingsState(hasFlipToLibrary = true, hasMoodleApp = true)
        for (school in listOf(false, true)) for (mail in listOf(false, true))
        for (library in listOf(false, true)) for (tigerSync in listOf(false, true)) {
            val state = phone.copy(schoolSignedIn = school, mailSignedIn = mail, librarySignedIn = library, tigerSyncOn = tigerSync)
            val schoolGate = R.string.settings_gate_school_sign_in.takeUnless { school }
            val mailGate = R.string.settings_gate_mail_sign_in.takeUnless { mail }
            val libraryGate = R.string.settings_gate_library_sign_in.takeUnless { library }
            val case = "school $school, mail $mail, library $library, TigerSync $tigerSync"
            assertEquals(
                case,
                mapOf(
                    SettingsRow.SHOW_FULL_DUE_DATE to null, SettingsRow.INVERT_SLIDER to null,
                    SettingsRow.ASSIGNMENT_REMINDERS to null, SettingsRow.LIVE_ACTIVITY to null,
                ),
                page(PageSettings.HOME, state),
            )
            assertEquals(
                case,
                listOf(
                    SettingsRow.PIN_TODAY_COURSES, SettingsRow.SHOW_CLASSROOM, SettingsRow.OUTLINE_REQUIRED_COURSES,
                    SettingsRow.SHOW_ALL_PERIODS, SettingsRow.COURSE_NAME_SIZE, SettingsRow.CLASS_REMINDERS,
                    SettingsRow.REASSIGN_COURSE_COLORS,
                ).associateWith { null },
                page(PageSettings.CLASS_TABLE, state),
            )
            assertEquals(case, mapOf(SettingsRow.BULLETIN_PUSH to schoolGate), page(PageSettings.BULLETINS, state))
            assertEquals(
                case,
                mapOf(
                    SettingsRow.MAIL_NOTIFICATIONS to mailGate, SettingsRow.MAIL_DISPLAY_NAME to mailGate,
                    SettingsRow.MAIL_GUIDE to null, SettingsRow.MAIL_STORAGE to null,
                ),
                page(PageSettings.MAIL, state),
            )
            assertEquals(
                case,
                if (library) {
                    mapOf(SettingsRow.FLIP_TO_LIBRARY to null, SettingsRow.LIBRARY_SIGN_OUT to null)
                } else {
                    mapOf(SettingsRow.FLIP_TO_LIBRARY to libraryGate)
                },
                page(PageSettings.LIBRARY, state),
            )
            assertEquals(
                case,
                mapOf(SettingsRow.LINK_OPENING_METHOD to null, SettingsRow.WEBSITE_DATA to null),
                page(PageSettings.BROWSER, state),
            )
            assertEquals(
                case,
                mapOf(SettingsRow.MOODLE_OPEN_IN to null, SettingsRow.PIN_MYSELF to null, SettingsRow.MOODLE_STORAGE to null),
                page(PageSettings.MOODLE, state),
            )
        }
    }

    @Test
    fun `the reminders and Live Updates take input with TigerSync off and nobody signed in`() {
        for (row in listOf(SettingsRow.CLASS_REMINDERS, SettingsRow.ASSIGNMENT_REMINDERS, SettingsRow.LIVE_ACTIVITY)) {
            assertNull("$row", row.gate)
            assertNull("$row", row.closedReason(SettingsState()))
        }
        assertTrue(SettingsRow.entries.none { it.gate == SettingsGate.TIGERSYNC })
    }

    @Test
    fun `each gate opens on its own condition`() {
        val closed = SettingsState()
        SettingsGate.entries.forEach { assertFalse("$it", it.isOpen(closed)) }
        assertTrue(SettingsGate.TIGERSYNC.isOpen(SettingsState(tigerSyncOn = true)))
        assertTrue(SettingsGate.SCHOOL_SIGN_IN.isOpen(SettingsState(schoolSignedIn = true)))
        assertTrue(SettingsGate.MAIL_SIGN_IN.isOpen(SettingsState(mailSignedIn = true)))
        assertTrue(SettingsGate.LIBRARY_SIGN_IN.isOpen(SettingsState(librarySignedIn = true)))
        assertFalse(SettingsGate.SCHOOL_SIGN_IN.isOpen(SettingsState(mailSignedIn = true, librarySignedIn = true, tigerSyncOn = true)))
    }

    @Test
    fun `a row its gate closes is still shown, and says why`() {
        val closed = SettingsState(hasFlipToLibrary = true)
        for (row in SettingsRow.entries.filter { it.gate != null }) {
            assertTrue("$row", row.isShown(closed))
            assertEquals("$row", row.gate?.reason, row.closedReason(closed))
            assertNotNull("$row", row.closedReason(closed))
        }
    }

    @Test
    fun `a row its condition hides is not shown`() {
        val bare = SettingsState()
        listOf(
            SettingsRow.FLIP_TO_LIBRARY, SettingsRow.LIBRARY_SIGN_OUT, SettingsRow.WHATS_NEW, SettingsRow.MOODLE_OPEN_IN,
            SettingsRow.CHECK_FOR_UPDATES, SettingsRow.ACCENT_GRADIENT_ON_WATCH, SettingsRow.ACCENT_GRADIENT_ON_LIVE_ACTIVITIES,
            SettingsRow.ENGLISH_COURSE_NAMES, SettingsRow.ENGLISH_CLASSROOM_NAMES, SettingsRow.MANDARIN_CLASSROOM_NAMES,
            SettingsRow.CLASS_TABLE_LIST,
        ).forEach { assertFalse("$it", it.isShown(bare)) }
        // The class tables' list, first on the class table's page, while anyone is imported.
        assertEquals(SettingsRow.CLASS_TABLE_LIST, PageSettings.CLASS_TABLE.rows.first { it.isShown(SettingsState(hasImportedClassTables = true)) })

        val english = SettingsState(coursesInEnglish = true)
        assertTrue(SettingsRow.ENGLISH_COURSE_NAMES.isShown(english))
        assertTrue(SettingsRow.ENGLISH_CLASSROOM_NAMES.isShown(english))
        assertFalse(SettingsRow.MANDARIN_CLASSROOM_NAMES.isShown(english))
        assertTrue(SettingsRow.MANDARIN_CLASSROOM_NAMES.isShown(english.copy(usesEnglishClassroomNames = true)))
        assertFalse(SettingsRow.MANDARIN_CLASSROOM_NAMES.isShown(SettingsState(usesEnglishClassroomNames = true)))
    }

    @Test
    fun `a page with nothing to show is hidden`() {
        val bare = SettingsState()
        assertFalse(PageSettings.LIBRARY.isShown(bare))
        assertTrue(PageSettings.LIBRARY.isShown(bare.copy(hasFlipToLibrary = true)))
        // Signed in, it has the sign-out, flip or no flip.
        assertTrue(PageSettings.LIBRARY.isShown(bare.copy(librarySignedIn = true)))
        PageSettings.entries.filter { it != PageSettings.LIBRARY }.forEach { assertTrue("$it", it.isShown(bare)) }
    }

    @Test
    fun `the root's rows sit in its sections, in order`() {
        assertEquals(
            listOf(
                SettingsSection.ACCOUNT, SettingsSection.APPEARANCE, SettingsSection.TIGERSYNC,
                SettingsSection.NOTIFICATION_PERMISSIONS, SettingsSection.LANGUAGE, SettingsSection.OTHER, SettingsSection.ABOUT,
            ),
            SettingsRow.entries.mapNotNull { (it.place as? SettingsPlace.Section)?.section }.distinct(),
        )
        assertEquals(
            listOf(SettingsRow.APP_ICON, SettingsRow.SCREEN_ROTATION, SettingsRow.VIBRATION, SettingsRow.API_ENDPOINT, SettingsRow.STORAGE, SettingsRow.ANALYTICS),
            SettingsRow.entries.filter { it.place == SettingsPlace.Section(SettingsSection.OTHER) },
        )
    }
}
