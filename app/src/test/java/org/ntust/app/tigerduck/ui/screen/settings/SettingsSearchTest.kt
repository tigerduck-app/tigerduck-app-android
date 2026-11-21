package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.component.PageSettings
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings' search (tigerduck-app#939): every word must be in the row's title or its section or
 * page name, case and width aside, and a hidden row is never found.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsSearchTest {
    private val context = RuntimeEnvironment.getApplication()
    private val label: (Int) -> String = context::getString

    private val everyRowShown = SettingsState(
        librarySignedIn = true, coursesInEnglish = true, usesEnglishClassroomNames = true, hasFlipToLibrary = true,
        hasWatch = true, hasLiveUpdateGradient = true, hasUpdateCheck = true, hasWhatsNew = true, hasMoodleApp = true,
    )

    private fun search(query: String, state: SettingsState = everyRowShown) = SettingsSearch.results(query, state, label)

    private fun found(row: SettingsRow, query: String, state: SettingsState = everyRowShown) = search(query, state).any { it.row == row }

    @Test
    fun `with every row shown, a row is found by its own title`() {
        SettingsRow.entries.forEach { assertTrue("$it", found(it, label(it.title))) }
    }

    @Test
    fun `a hidden row is not found, a closed one is`() {
        val bare = SettingsState()
        listOf(SettingsRow.FLIP_TO_LIBRARY, SettingsRow.LIBRARY_SIGN_OUT, SettingsRow.WHATS_NEW, SettingsRow.MOODLE_OPEN_IN)
            .forEach { assertFalse("$it", found(it, label(it.title), bare)) }
        assertTrue(found(SettingsRow.MAIL_NOTIFICATIONS, label(SettingsRow.MAIL_NOTIFICATIONS.title), bare))
        assertTrue(found(SettingsRow.BULLETIN_PUSH, label(SettingsRow.BULLETIN_PUSH.title), bare))
    }

    @Test
    fun `matching ignores case and character width`() {
        val title = label(SettingsRow.SHOW_CLASSROOM.title)
        assertTrue(found(SettingsRow.SHOW_CLASSROOM, title.uppercase()))
        assertTrue(found(SettingsRow.SHOW_CLASSROOM, title.lowercase()))
        // What a Chinese keyboard types in full-width mode.
        assertTrue(found(SettingsRow.TIGERSYNC, "ＴＩＧＥＲｓｙｎｃ"))
        assertTrue(found(SettingsRow.API_ENDPOINT, "ａｐｉ"))
    }

    @Test
    fun `every word must match, from the page name or the title`() {
        val page = label(PageSettings.CLASS_TABLE.title)
        val byPage = search(page).map { it.row }
        assertTrue(byPage.containsAll(PageSettings.CLASS_TABLE.rows))
        assertTrue(found(SettingsRow.SHOW_CLASSROOM, "$page ${label(SettingsRow.SHOW_CLASSROOM.title)}"))
        // A full-width space between the words, as a Chinese keyboard puts one.
        assertTrue(found(SettingsRow.SHOW_CLASSROOM, "${page}　${label(SettingsRow.SHOW_CLASSROOM.title)}"))
        assertTrue(search("$page zzzz-no-such-setting").isEmpty())
        assertTrue(search("   ").isEmpty())
        assertTrue(search("").isEmpty())
    }

    @Test
    fun `a result reads its page or section, then its title`() {
        val result = search(label(SettingsRow.SHOW_CLASSROOM.title)).first { it.row == SettingsRow.SHOW_CLASSROOM }
        assertEquals("${label(PageSettings.CLASS_TABLE.title)} › ${label(SettingsRow.SHOW_CLASSROOM.title)}", result.text)
        assertEquals(
            listOf(label(SettingsSection.OTHER.title!!), label(SettingsRow.APP_ICON.title)),
            SettingsSearch.path(SettingsRow.APP_ICON, label),
        )
        // A section named as its one row, and one with no name, give the title alone.
        assertEquals(listOf(label(SettingsRow.TIGERSYNC.title)), SettingsSearch.path(SettingsRow.TIGERSYNC, label))
        assertEquals(
            listOf(label(SettingsRow.NOTIFICATION_PERMISSIONS.title)),
            SettingsSearch.path(SettingsRow.NOTIFICATION_PERMISSIONS, label),
        )
    }
}
