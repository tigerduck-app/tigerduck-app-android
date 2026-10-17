package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.WidgetItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A shortcut drag in edit mode is one gesture from touch-down to lift,
 * however many slots it crosses. Four cards, two rows:
 *
 *     Announcements  Scores
 *     Free rooms     Calendar
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShortcutGridDragTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val features = listOf(
        AppFeature.ANNOUNCEMENTS, AppFeature.SCORE, AppFeature.EMPTY_CLASSROOM, AppFeature.CALENDAR,
    )
    private val moves = mutableListOf<Pair<String, Int>>()
    private lateinit var widgets: MutableState<List<WidgetItem>>

    private fun text(@StringRes id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show() {
        composeRule.setContent {
            widgets = remember { mutableStateOf(features.map { WidgetItem("w-${it.id}", it) }) }
            ShortcutGrid(
                widgets = widgets.value,
                isEditing = true,
                onOpen = {},
                onRemove = {},
                onMove = { id, to ->
                    // What HomeViewModel.moveWidget does, without the view model.
                    moves += id to to
                    val list = widgets.value.toMutableList()
                    list.add(to, list.removeAt(list.indexOfFirst { it.id == id }))
                    widgets.value = list
                },
                onAdd = {},
            )
        }
    }

    private fun card(feature: AppFeature): SemanticsNodeInteraction =
        composeRule.onNodeWithText(text(feature.displayNameRes))

    private fun bounds(feature: AppFeature): Rect = card(feature).fetchSemanticsNode().boundsInRoot

    @Test
    fun `one drag can cross into the next row and keep going`() {
        show()
        val pitchX = bounds(AppFeature.SCORE).left - bounds(AppFeature.ANNOUNCEMENTS).left
        val pitchY = bounds(AppFeature.EMPTY_CLASSROOM).top - bounds(AppFeature.ANNOUNCEMENTS).top

        // Separate injections with an idle between them, so the card has been
        // recomposed in its new row before the finger moves on: the gesture
        // must survive that, as a real one does.
        card(AppFeature.ANNOUNCEMENTS).performTouchInput { down(center) }
        card(AppFeature.ANNOUNCEMENTS).performTouchInput { moveBy(Offset(0f, pitchY)) }
        composeRule.waitForIdle()
        assertEquals(
            listOf(AppFeature.SCORE, AppFeature.EMPTY_CLASSROOM, AppFeature.ANNOUNCEMENTS, AppFeature.CALENDAR),
            widgets.value.map { it.feature },
        )

        card(AppFeature.ANNOUNCEMENTS).performTouchInput { moveBy(Offset(pitchX, 0f)) }
        composeRule.waitForIdle()
        card(AppFeature.ANNOUNCEMENTS).performTouchInput { up() }
        composeRule.waitForIdle()

        assertEquals(
            listOf(AppFeature.SCORE, AppFeature.EMPTY_CLASSROOM, AppFeature.CALENDAR, AppFeature.ANNOUNCEMENTS),
            widgets.value.map { it.feature },
        )
        assertEquals(listOf("w-announcements" to 2, "w-announcements" to 3), moves)
    }

    @Test
    fun `a drag can come back to where it started`() {
        show()
        val pitchY = bounds(AppFeature.EMPTY_CLASSROOM).top - bounds(AppFeature.ANNOUNCEMENTS).top

        card(AppFeature.ANNOUNCEMENTS).performTouchInput { down(center) }
        card(AppFeature.ANNOUNCEMENTS).performTouchInput { moveBy(Offset(0f, pitchY)) }
        composeRule.waitForIdle()
        card(AppFeature.ANNOUNCEMENTS).performTouchInput { moveBy(Offset(0f, -pitchY)) }
        composeRule.waitForIdle()
        card(AppFeature.ANNOUNCEMENTS).performTouchInput { up() }
        composeRule.waitForIdle()

        assertEquals(features, widgets.value.map { it.feature })
        assertEquals(listOf("w-announcements" to 2, "w-announcements" to 0), moves)
    }
}
