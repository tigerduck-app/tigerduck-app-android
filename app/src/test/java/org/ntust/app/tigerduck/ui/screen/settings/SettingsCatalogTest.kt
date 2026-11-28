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

    private fun section(section: SettingsSection) = SettingsRow.entries.filter { it.place == SettingsPlace.Section(section) }

    private fun subPage(subPage: SettingsSubPage) = SettingsRow.entries.filter { it.place == SettingsPlace.SubPage(subPage) }

    @Test
    fun `every row has a title and every page a row`() {
        SettingsRow.entries.forEach { assertTrue("$it has no title", context.getString(it.title).isNotBlank()) }
        PageSettings.entries.forEach { assertTrue("$it lists no row", it.rows.isNotEmpty()) }
        SettingsSubPage.entries.forEach { assertTrue("$it lists no row", subPage(it).isNotEmpty()) }
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
                    SettingsRow.REASSIGN_COURSE_COLORS, SettingsRow.CLASS_TABLE_STORAGE,
                ).associateWith { null },
                page(PageSettings.CLASS_TABLE, state),
            )
            assertEquals(case, mapOf(SettingsRow.BULLETIN_PUSH to schoolGate), page(PageSettings.BULLETINS, state))
            assertEquals(
                case,
                mapOf(
                    SettingsRow.MAIL_NOTIFICATIONS to mailGate, SettingsRow.MAIL_DISPLAY_NAME to mailGate,
                    SettingsRow.MAIL_STORAGE to null,
                ),
                page(PageSettings.MAIL, state),
            )
            // Signed in or not, no sign-out: that is the Account section's, and the library's ⋮.
            assertEquals(case, mapOf(SettingsRow.FLIP_TO_LIBRARY to libraryGate), page(PageSettings.LIBRARY, state))
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
            assertEquals(
                case,
                listOf(
                    SettingsRow.NOTIFICATION_LIVE_ACTIVITY to null, SettingsRow.NOTIFICATION_ASSIGNMENT_REMINDERS to null,
                    SettingsRow.NOTIFICATION_CLASS_REMINDERS to null, SettingsRow.NOTIFICATION_BULLETINS to schoolGate,
                    SettingsRow.NOTIFICATION_MAIL to mailGate,
                ),
                subPage(SettingsSubPage.NOTIFICATION_SWITCHES).map { it to it.closedReason(state) },
            )
        }
    }

    @Test
    fun `the reminders and Live Updates take input with TigerSync off and nobody signed in`() {
        for (row in listOf(
            SettingsRow.CLASS_REMINDERS, SettingsRow.ASSIGNMENT_REMINDERS, SettingsRow.LIVE_ACTIVITY,
            SettingsRow.NOTIFICATION_CLASS_REMINDERS, SettingsRow.NOTIFICATION_ASSIGNMENT_REMINDERS,
            SettingsRow.NOTIFICATION_LIVE_ACTIVITY,
        )) {
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
            SettingsRow.FLIP_TO_LIBRARY, SettingsRow.WHATS_NEW, SettingsRow.MOODLE_OPEN_IN,
            SettingsRow.CHECK_FOR_UPDATES, SettingsRow.ACCENT_GRADIENT_ON_WATCH, SettingsRow.ACCENT_GRADIENT_ON_LIVE_ACTIVITIES,
            SettingsRow.ENGLISH_COURSE_NAMES, SettingsRow.ENGLISH_CLASSROOM_NAMES, SettingsRow.MANDARIN_CLASSROOM_NAMES,
            SettingsRow.CLASS_TABLE_LIST,
        ).forEach { assertFalse("$it", it.isShown(bare)) }
        // The class tables' list, first on the class table's page, while anyone is imported.
        assertEquals(SettingsRow.CLASS_TABLE_LIST, PageSettings.CLASS_TABLE.rows.first { it.isShown(SettingsState(hasImportedClassTables = true)) })
        // Gradient on Wear OS once a watch has been seen on this phone.
        assertTrue(SettingsRow.ACCENT_GRADIENT_ON_WATCH.isShown(SettingsState(hasWatch = true)))

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
        // The library's page has only flip-to-library: on a phone that cannot sense the flip, none,
        // signed in to the library or not.
        assertFalse(PageSettings.LIBRARY.isShown(bare))
        assertFalse(PageSettings.LIBRARY.isShown(bare.copy(librarySignedIn = true)))
        assertTrue(PageSettings.LIBRARY.isShown(bare.copy(hasFlipToLibrary = true)))
        PageSettings.entries.filter { it != PageSettings.LIBRARY }.forEach { assertTrue("$it", it.isShown(bare)) }
    }

    @Test
    fun `the root's parts come in their order, and its sections' rows in theirs`() {
        assertEquals(
            listOf(
                RootPart.ACCOUNT, RootPart.APPEARANCE, RootPart.NOTIFICATIONS, RootPart.PAGES, RootPart.TIGERSYNC,
                RootPart.LANGUAGE, RootPart.OTHER, RootPart.ABOUT, RootPart.DEVELOPER,
            ),
            RootPart.entries.toList(),
        )
        // The catalog's sections are the root's parts but the pages' list and the debug build's own.
        assertEquals(
            RootPart.entries.filter { it != RootPart.PAGES && it != RootPart.DEVELOPER },
            SettingsSection.entries.map(RootPart::of),
        )
        assertEquals(
            listOf(SettingsRow.SCHOOL_ACCOUNT, SettingsRow.LIBRARY_ACCOUNT, SettingsRow.MAIL_ACCOUNT),
            section(SettingsSection.ACCOUNT),
        )
        assertEquals(
            listOf(
                SettingsRow.ACCENT_COLOR, SettingsRow.GRADIENT_USAGE, SettingsRow.VISUAL_PRESET, SettingsRow.COLOR_THEME,
                SettingsRow.DISPLAY_SIZE, SettingsRow.TAB_BAR,
            ),
            section(SettingsSection.APPEARANCE),
        )
        assertEquals(
            listOf(SettingsRow.NOTIFICATION_PERMISSIONS, SettingsRow.NOTIFICATION_SWITCHES),
            section(SettingsSection.NOTIFICATIONS),
        )
        assertEquals(listOf(SettingsRow.TIGERSYNC), section(SettingsSection.TIGERSYNC))
        assertEquals(listOf(SettingsRow.LANGUAGE), section(SettingsSection.LANGUAGE))
        assertEquals(listOf(SettingsRow.APP_ICON, SettingsRow.STORAGE, SettingsRow.OTHER_SETTINGS), section(SettingsSection.OTHER))
        assertEquals(
            listOf(SettingsRow.VERSION, SettingsRow.CHECK_FOR_UPDATES, SettingsRow.WHATS_NEW, SettingsRow.WEBSITE, SettingsRow.ABOUT_OTHERS),
            section(SettingsSection.ABOUT),
        )
    }

    @Test
    fun `the Pages section lists the app's pages, and Other settings the browser's and Moodle's`() {
        assertEquals(
            listOf(PageSettings.HOME, PageSettings.CLASS_TABLE, PageSettings.BULLETINS, PageSettings.MAIL, PageSettings.LIBRARY),
            PageSettings.entries.filter { it.linkedFrom == RootPart.PAGES },
        )
        assertEquals(
            listOf(PageSettings.BROWSER, PageSettings.MOODLE),
            PageSettings.entries.filter { it.linkedFrom == RootPart.OTHER },
        )
    }

    @Test
    fun `each sub-page lists its rows in order, opened by a row of its section titled as it`() {
        assertEquals(
            listOf(
                SettingsRow.ACCENT_GRADIENT_ON_WIDGETS, SettingsRow.ACCENT_GRADIENT_ON_WATCH,
                SettingsRow.ACCENT_GRADIENT_ON_LIVE_ACTIVITIES,
            ),
            subPage(SettingsSubPage.GRADIENT_USAGE),
        )
        assertEquals(
            listOf(
                SettingsRow.NOTIFICATION_LIVE_ACTIVITY, SettingsRow.NOTIFICATION_ASSIGNMENT_REMINDERS,
                SettingsRow.NOTIFICATION_CLASS_REMINDERS, SettingsRow.NOTIFICATION_BULLETINS, SettingsRow.NOTIFICATION_MAIL,
            ),
            subPage(SettingsSubPage.NOTIFICATION_SWITCHES),
        )
        assertEquals(
            listOf(SettingsRow.API_ENDPOINT, SettingsRow.VIBRATION, SettingsRow.SCREEN_ROTATION, SettingsRow.ANALYTICS),
            subPage(SettingsSubPage.OTHER_SETTINGS),
        )
        // Server status, first on About › Others.
        assertEquals(listOf(SettingsRow.SERVER_STATUS), subPage(SettingsSubPage.ABOUT_OTHERS))

        assertEquals(
            mapOf(
                SettingsSubPage.GRADIENT_USAGE to (SettingsRow.GRADIENT_USAGE to SettingsSection.APPEARANCE),
                SettingsSubPage.NOTIFICATION_SWITCHES to (SettingsRow.NOTIFICATION_SWITCHES to SettingsSection.NOTIFICATIONS),
                SettingsSubPage.OTHER_SETTINGS to (SettingsRow.OTHER_SETTINGS to SettingsSection.OTHER),
                SettingsSubPage.ABOUT_OTHERS to (SettingsRow.ABOUT_OTHERS to SettingsSection.ABOUT),
            ),
            SettingsSubPage.entries.associateWith { it.opener to it.section },
        )
        SettingsSubPage.entries.forEach {
            assertEquals("$it", it.opener.title, it.title)
            assertEquals("$it", SettingsPlace.Section(it.section), it.opener.place)
        }
        assertEquals(R.string.settings_accent_gradient_usage, SettingsSubPage.GRADIENT_USAGE.title)
        assertEquals(R.string.settings_notification_switches, SettingsSubPage.NOTIFICATION_SWITCHES.title)
        assertEquals(R.string.settings_section_other_settings, SettingsSubPage.OTHER_SETTINGS.title)
    }

    @Test
    fun `each sub-page opens a page of its own`() {
        assertEquals(SettingsSubPage.entries.size, SettingsSubPage.entries.map { it.link }.toSet().size)
        assertEquals(SettingsLink.ABOUT_OTHERS, SettingsSubPage.ABOUT_OTHERS.link)
    }
}
