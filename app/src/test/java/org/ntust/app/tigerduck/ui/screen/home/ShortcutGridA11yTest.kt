package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.WidgetItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What TalkBack gets from a shortcut card: a button outside edit mode; in
 * edit mode, the drag and the cross as actions it can perform, each one
 * naming the card it acts on, and a move saying where the card landed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class ShortcutGridA11yTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val features = listOf(AppFeature.ANNOUNCEMENTS, AppFeature.SCORE, AppFeature.EMPTY_CLASSROOM)
    private val opened = mutableListOf<AppFeature>()
    private val removed = mutableListOf<String>()
    private val moves = mutableListOf<Pair<String, Int>>()
    private val steps = mutableListOf<Pair<String, Int>>()
    /** What a step answers: where the card landed, or null when nothing moved. */
    private var landing: HomeSectionLayout.Step? = HomeSectionLayout.Step(emptyList(), position = 3, count = 3)

    private fun text(@StringRes id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show(isEditing: Boolean) {
        composeRule.setContent {
            ShortcutGrid(
                widgets = features.map { WidgetItem("w-${it.id}", it) },
                isEditing = isEditing,
                onOpen = { opened += it },
                onRemove = { removed += it.id },
                onMove = { id, to -> moves += id to to },
                onStep = { id, by -> steps += id to by; landing },
                onAdd = {},
            )
        }
    }

    private fun card(feature: AppFeature) = composeRule.onNodeWithText(text(feature.displayNameRes))

    private fun actions(feature: AppFeature) = card(feature)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsActions.CustomActions)
        .orEmpty()

    private val moveUp get() = text(R.string.a11y_home_edit_move_up)
    private val moveDown get() = text(R.string.a11y_home_edit_move_down)

    /** What was said through TalkBack's announcements, with it on. */
    private fun announced(): List<String> =
        shadowOf(composeRule.activity.getSystemService(AccessibilityManager::class.java))
            .sentAccessibilityEvents
            .filter { it.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT }
            .map { it.text.joinToString("") }

    private fun talkBackOn() =
        shadowOf(composeRule.activity.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
    private val remove get() = text(R.string.action_remove)

    @Test
    fun `outside edit mode a card is a button that opens its feature`() {
        show(isEditing = false)

        card(AppFeature.SCORE).assertHasClickAction().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(AppFeature.SCORE), opened)
        assertEquals(emptyList<String>(), actions(AppFeature.SCORE).map { it.label })
    }

    @Test
    fun `in edit mode a card is not a button`() {
        // A double tap would do nothing, so TalkBack must not offer one.
        show(isEditing = true)

        card(AppFeature.SCORE).assertHasNoClickAction()
    }

    @Test
    fun `the remove control names the card it removes`() {
        // Three crosses that each say only "Remove" cannot be told apart.
        show(isEditing = true)

        composeRule
            .onNodeWithContentDescription("$remove ${text(R.string.feature_score)}")
            .performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("w-score"), removed)
    }

    @Test
    fun `a card offers moving up and down, and removing, as actions`() {
        show(isEditing = true)

        val actions = actions(AppFeature.SCORE)
        assertEquals(listOf(moveUp, moveDown, remove), actions.map { it.label })

        actions.forEach { it.action() }
        composeRule.waitForIdle()

        // One place each way, from where the card is when the action runs, not a slot fixed
        // when the action was offered.
        assertEquals(listOf("w-score" to -1, "w-score" to 1), steps)
        assertEquals(emptyList<Pair<String, Int>>(), moves)
        assertEquals(listOf("w-score"), removed)
    }

    @Test
    fun `a move says where the card landed`() {
        show(isEditing = true)
        talkBackOn()

        actions(AppFeature.SCORE).first { it.label == moveDown }.action()
        composeRule.waitForIdle()

        assertEquals(listOf("Position 3 of 3"), announced())
    }

    @Test
    fun `a move that moved nothing says nothing`() {
        // A stale action: the card has gone, or already reached the end.
        landing = null
        show(isEditing = true)
        talkBackOn()

        val handled = actions(AppFeature.SCORE).first { it.label == moveDown }.action()
        composeRule.waitForIdle()

        assertEquals(false, handled)
        assertEquals(emptyList<String>(), announced())
    }

    @Test
    fun `the first card cannot move before and the last cannot move after`() {
        show(isEditing = true)

        assertEquals(listOf(moveDown, remove), actions(AppFeature.ANNOUNCEMENTS).map { it.label })
        assertEquals(listOf(moveUp, remove), actions(AppFeature.EMPTY_CLASSROOM).map { it.label })
    }
}
