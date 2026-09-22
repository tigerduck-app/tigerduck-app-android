package org.ntust.app.tigerduck.ui.screen.announcements

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.component.listTopAnchor

/**
 * What asks the bulletin list for its next page: the id of the last bulletin on screen. On a real
 * `LazyColumn` built the way the bulletin list is -- an anchor above the bulletins, a spinner and
 * a spacer below them -- because what has to come out right is which of those the list reports.
 */
@RunWith(AndroidJUnit4::class)
class AnnouncementsPaginationTest {

    @get:Rule
    val rule = createComposeRule()

    private val bulletins = (100 until 112).toList()

    private fun show(loaded: List<Int> = bulletins, paginating: Boolean = false): LazyListState {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            LazyColumn(state = state, modifier = Modifier.height(300.dp)) {
                listTopAnchor()
                items(loaded, key = { it }) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                if (paginating) item(key = "pagination-spinner") { Box(Modifier.fillMaxWidth().height(48.dp)) }
                item(key = "bottom-spacer") { Spacer(Modifier.height(8.dp)) }
            }
        }
        return state
    }

    /** Scrolls until the end of the list is on screen, which is when another page is due. */
    private fun LazyListState.scrollToTheEnd() = rule.runOnIdle {
        runBlocking { scrollToItem(layoutInfo.totalItemsCount - 1) }
    }

    @Test
    fun theLastBulletinLoadedIsTheOneThatAsksForTheNextPage() {
        val state = show()
        state.scrollToTheEnd()
        rule.runOnIdle {
            assertEquals(111, state.layoutInfo.lastVisibleBulletinId())
            // The page that never got asked for: that bulletin's place in the list is one past
            // its place among the bulletins, because the anchor sits above them.
            val place = state.layoutInfo.visibleItemsInfo.last { it.key is Int }.index
            assertNull("a place in the list is not a place among the bulletins", bulletins.getOrNull(place))
        }
    }

    @Test
    fun theSpinnerAndTheSpacerBelowTheBulletinsAreNotBulletins() {
        // Both are on screen at the bottom, and both are keyed by String so that they are passed
        // over. Were either reported instead, the list would stop asking after one more page.
        val state = show(paginating = true)
        state.scrollToTheEnd()
        rule.runOnIdle {
            val keys = state.layoutInfo.visibleItemsInfo.map { it.key }
            assertTrue("both are on screen, got $keys", "pagination-spinner" in keys && "bottom-spacer" in keys)
            assertEquals(111, state.layoutInfo.lastVisibleBulletinId())
        }
    }

    @Test
    fun theAnchorAloneIsNotABulletin() {
        // Before the first page lands the list is its anchor and its spacer, and nothing is due.
        val state = show(loaded = emptyList())
        rule.runOnIdle { assertNull(state.layoutInfo.lastVisibleBulletinId()) }
    }

    @Test
    fun aListAtItsTopReportsABulletinFromTheTopOfIt() {
        // Which is one the view model will not act on: it asks for more within five of the end.
        val state = show()
        rule.runOnIdle {
            val id = state.layoutInfo.lastVisibleBulletinId()
            assertTrue("reported a bulletin, got $id", id in bulletins)
            assertNotEquals(111, id)
        }
    }
}
