package org.ntust.app.tigerduck.ui.screen.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.AppFeature.CALENDAR
import org.ntust.app.tigerduck.data.model.AppFeature.CLASS_TABLE
import org.ntust.app.tigerduck.data.model.AppFeature.HOME
import org.ntust.app.tigerduck.data.model.AppFeature.LIBRARY
import org.ntust.app.tigerduck.data.model.AppFeature.SCHOOL_MAIL
import org.ntust.app.tigerduck.data.model.AppFeature.SCORE

class LibraryPagesTest {

    private var bar = listOf(HOME, CLASS_TABLE, SCHOOL_MAIL)

    private fun ask(signedIn: Boolean) =
        libraryBottomBarPage(isSignedIn = { signedIn }, tabs = { bar }, setTabs = { bar = it })

    @Test
    fun `library goes last, left of more, on a bar with room`() {
        assertEquals(listOf(HOME, CLASS_TABLE, SCHOOL_MAIL, LIBRARY), tabsAddingLibrary(listOf(HOME, CLASS_TABLE, SCHOOL_MAIL)))
        assertEquals(listOf(HOME, LIBRARY), tabsAddingLibrary(listOf(HOME)))
    }

    @Test
    fun `a full bar or one with library is not asked`() {
        assertNull(tabsAddingLibrary(listOf(HOME, CLASS_TABLE, CALENDAR, SCORE)))
        assertNull(tabsAddingLibrary(listOf(LIBRARY, HOME)))
    }

    @Test
    fun `someone not signed in to the library sees the library page`() {
        assertTrue(libraryPage(isSignedIn = { false }).isApplicable())
        assertFalse(libraryPage(isSignedIn = { true }).isApplicable())
    }

    @Test
    fun `the question comes only with the library page, and only with room on the bar`() {
        assertTrue(ask(signedIn = false).isApplicable())
        assertFalse(ask(signedIn = true).isApplicable())
        bar = listOf(HOME, CLASS_TABLE, SCHOOL_MAIL, SCORE)
        assertFalse(ask(signedIn = false).isApplicable())
    }

    @Test
    fun `confirming stores the bar with library`() {
        ask(signedIn = false).apply()
        assertEquals(listOf(HOME, CLASS_TABLE, SCHOOL_MAIL, LIBRARY), bar)
    }

    @Test
    fun `a bar filled before the question comes up shows where library is instead`() {
        bar = listOf(HOME, CLASS_TABLE, SCORE, SCHOOL_MAIL)
        assertEquals("Library is in More", ask(signedIn = false).instead()?.title?.en)
    }

    @Test
    fun `with room, or with library on the bar, the question itself shows`() {
        assertNull(ask(signedIn = false).instead())
        bar = listOf(HOME, CLASS_TABLE, SCORE, LIBRARY)
        assertNull(ask(signedIn = false).instead())
    }

    @Test
    fun `the 2_3_x default the upgrade keeps has room for library`() {
        assertEquals(AppFeature.defaultTabs, tabsAddingLibrary(AppFeature.defaultTabsBeforeLibrary))
    }
}
