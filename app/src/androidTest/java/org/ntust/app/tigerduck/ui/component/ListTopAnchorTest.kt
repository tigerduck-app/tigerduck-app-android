package org.ntust.app.tigerduck.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mail list and the bulletin list both paint their cache first and let a refresh land newer
 * rows ahead of it. Real `LazyColumn`s, because the behaviour under test is the list's own.
 */
@RunWith(AndroidJUnit4::class)
class ListTopAnchorTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(
        rows: MutableState<List<Int>>,
        anchored: Boolean = true,
        footer: Boolean = false,
        topPadding: Int = 0,
    ): LazyListState {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            LazyColumn(state = state, modifier = Modifier.height(300.dp), contentPadding = PaddingValues(top = topPadding.dp)) {
                if (anchored) listTopAnchor()
                items(rows.value, key = { it }) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                if (footer) item(key = "footer") { Box(Modifier.fillMaxWidth().height(8.dp)) }
            }
        }
        return state
    }

    /** The first real row on screen, whatever sits above it. */
    private fun LazyListState.firstRow(): Any? = layoutInfo.visibleItemsInfo.firstOrNull { it.key is Int }?.key

    @Test
    fun aListAtItsTopStaysThereWhenNewerRowsArriveAboveTheCache() {
        val rows = mutableStateOf((10 until 40).toList())
        val state = show(rows)
        rule.runOnIdle { rows.value = (0 until 40).toList() }
        rule.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertEquals(0, state.firstRow())
        }
    }

    @Test
    fun theSameUnderTheRoomLeftForAChromeOverlay() {
        // The mail and bulletin lists pad their top by the height of the header drawn over them.
        val rows = mutableStateOf((10 until 40).toList())
        val state = show(rows, topPadding = 120)
        rule.runOnIdle { rows.value = (0 until 40).toList() }
        rule.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstRow())
        }
    }

    @Test
    fun withoutTheAnchorTheListOpensOnTheCachesFirstRow() {
        // The bug itself: the list holds on to row 10 and the ten newer rows go in above the viewport.
        val rows = mutableStateOf((10 until 40).toList())
        val state = show(rows, anchored = false)
        rule.runOnIdle { rows.value = (0 until 40).toList() }
        rule.runOnIdle { assertEquals(10, state.firstRow()) }
    }

    @Test
    fun aListScrolledDownKeepsTheRowBeingRead() {
        val rows = mutableStateOf((10 until 40).toList())
        val state = show(rows)
        // Index 11 is row 20: the anchor is index 0.
        rule.runOnIdle { runBlocking { state.scrollToItem(11) } }
        rule.runOnIdle { rows.value = (0 until 40).toList() }
        rule.runOnIdle { assertEquals(20, state.firstRow()) }
    }

    @Test
    fun aFooterAloneIsNotWhatTheListHoldsOnTo() {
        // Before the first page arrives the list is only its footer. It used to be the first
        // visible item, and holding it in place opened the list at its end.
        val rows = mutableStateOf(emptyList<Int>())
        val state = show(rows, footer = true)
        rule.runOnIdle { rows.value = (0 until 40).toList() }
        rule.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstRow())
        }
    }
}
