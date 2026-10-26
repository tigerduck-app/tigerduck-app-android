package org.ntust.app.tigerduck.ui.screen.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.R

/** The line under the search field that says a search may have missed mail, and why. */
class SearchNoteTest {

    @Test
    fun `a search run before every mail was downloaded may miss older mail`() {
        assertEquals(R.string.school_mail_search_index_partial, searchNote(searched = true, loadedOnly = false, partial = true))
    }

    @Test
    fun `a search that could read only the loaded mail says so, partial or not`() {
        assertEquals(R.string.school_mail_search_local_only, searchNote(searched = true, loadedOnly = true, partial = true))
        assertEquals(R.string.school_mail_search_local_only, searchNote(searched = true, loadedOnly = true, partial = false))
    }

    @Test
    fun `no search, or one over all the mail, needs no note`() {
        assertNull(searchNote(searched = false, loadedOnly = false, partial = true))
        assertNull(searchNote(searched = true, loadedOnly = false, partial = false))
    }
}
