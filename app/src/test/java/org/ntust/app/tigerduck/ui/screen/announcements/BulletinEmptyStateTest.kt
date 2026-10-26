package org.ntust.app.tigerduck.ui.screen.announcements

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.R

class BulletinEmptyStateTest {
    @Test
    fun `nothing loaded at all says there are no bulletins, filters or not`() {
        assertEquals(
            R.string.bulletin_no_bulletins_title to R.string.bulletin_no_bulletins_message,
            bulletinEmptyText(hasItems = false, unreadOnly = false, hasActiveFilter = true),
        )
    }

    @Test
    fun `a filter or search that hides everything says nothing matches`() {
        assertEquals(
            R.string.bulletin_no_bulletins_title to R.string.bulletin_no_match_message,
            bulletinEmptyText(hasItems = true, unreadOnly = false, hasActiveFilter = true),
        )
    }

    @Test
    fun `unread only with everything read says there is nothing unread`() {
        assertEquals(
            R.string.bulletin_no_unread_title to R.string.bulletin_no_match_message,
            bulletinEmptyText(hasItems = true, unreadOnly = true, hasActiveFilter = false),
        )
    }

    @Test
    fun `a search counts as a filter, departments and categories too`() {
        val state = AnnouncementsViewModel.State()
        assertEquals(false, state.hasActiveFilter)
        assertEquals(true, state.copy(searchText = "獎學金").hasActiveFilter)
        assertEquals(true, state.copy(selectedOrgs = setOf("academic")).hasActiveFilter)
        assertEquals(true, state.copy(selectedTags = setOf("scholarship")).hasActiveFilter)
        assertEquals(false, state.copy(searchText = "  ").hasActiveFilter)
    }

    @Test
    fun `a failed refresh shows its failure only while there is nothing to show at all`() {
        val failed = AnnouncementsViewModel.LoadState.Failed("offline")
        assertEquals(BulletinPlaceholder.FAILED, bulletinPlaceholder(hasItems = false, hasDisplayed = false, loadState = failed))
        // Bulletins a search hides are still there: the search, not the refresh, emptied the list.
        assertEquals(BulletinPlaceholder.EMPTY, bulletinPlaceholder(hasItems = true, hasDisplayed = false, loadState = failed))
        assertEquals(null, bulletinPlaceholder(hasItems = true, hasDisplayed = true, loadState = failed))
    }

    @Test
    fun `nothing loaded yet is neither empty nor failed`() {
        assertEquals(null, bulletinPlaceholder(hasItems = false, hasDisplayed = false, loadState = AnnouncementsViewModel.LoadState.Loading))
        assertEquals(
            BulletinPlaceholder.EMPTY,
            bulletinPlaceholder(hasItems = false, hasDisplayed = false, loadState = AnnouncementsViewModel.LoadState.Loaded),
        )
    }
}
