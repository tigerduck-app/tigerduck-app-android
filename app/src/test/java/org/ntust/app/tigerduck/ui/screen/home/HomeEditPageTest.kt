package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.AssignmentFilter
import org.ntust.app.tigerduck.data.model.HomeSection
import org.ntust.app.tigerduck.data.model.HomeSection.HomeSectionType
import org.ntust.app.tigerduck.data.model.WidgetItem
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.ui.haptics.Haptics
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Home while it is being arranged: each section an outline with a drag handle above its header
 * and a remove button that names it, nothing of its content, and a page that still scrolls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = HomeEditPageTest.HapticsApp::class, qualifiers = "en")
class HomeEditPageTest {

    /**
     * Not the real app, a Hilt app these tests do not need, but enough of one to answer the
     * haptic a pull past the refresh line asks its preferences for.
     */
    class HapticsApp : Application(), GeneratedComponentManager<Any> {
        private val component by lazy {
            object : Haptics.PrefsEntryPoint, GeneratedComponent {
                override fun appPreferences() = AppPreferences(this@HapticsApp)
            }
        }

        override fun generatedComponent(): Any = component
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun text(@StringRes id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private val today = HomeSection("today-courses", HomeSectionType.TODAY_COURSES, "Today's courses", 0, true)
    private val assignments = HomeSection("upcoming-assignments", HomeSectionType.UPCOMING_ASSIGNMENTS, "Assignments", 1, true)
    private val quick = HomeSection(
        "quick-widgets", HomeSectionType.QUICK_WIDGETS, "Quick widgets", 2, true,
        widgets = listOf(WidgetItem("w-score", AppFeature.SCORE)),
    )
    private fun custom(n: Int) = HomeSection("custom-$n", HomeSectionType.CUSTOM, "Section $n", n, true)

    private val removed = mutableListOf<String>()
    private val removedWidgets = mutableListOf<String>()
    private val filters = mutableListOf<AssignmentFilter>()
    private var refreshes = 0
    private val movedWidgets = mutableListOf<String>()
    private val editing = mutableStateOf(false)
    // A flow, as HomeViewModel's: a move reaches the page a little after the drag that made it.
    private val sections = MutableStateFlow(emptyList<HomeSection>())
    private lateinit var listState: LazyListState
    private lateinit var timeSlider: TimeSliderViewModel

    /** The classes the time slider shows; with none, a stand-in takes its place, since the real one ticks. */
    private var courses = emptyList<Course>()

    private fun show(sections: List<HomeSection>, isEditing: Boolean) {
        this.sections.value = sections
        editing.value = isEditing
        composeRule.setContent {
            listState = rememberLazyListState()
            // Outside the page, where HomeScreen holds it.
            timeSlider = remember { TimeSliderViewModel() }
            val shown by this.sections.collectAsState()
            TigerDuckAppTheme(darkTheme = false) {
                HomePage(
                    sections = shown,
                    isEditing = editing.value,
                    onStartEditing = { editing.value = true },
                    isRefreshing = false,
                    onRefresh = { refreshes++ },
                    // What HomeViewModel.moveSections does, without the view model.
                    onMoveSection = { from, to -> this.sections.value = HomeSectionLayout.move(this.sections.value, from, to) },
                    onRemoveSection = { removed += it },
                    onRemoveWidget = { _, widgetId -> removedWidgets += widgetId },
                    onMoveWidget = { _, widgetId, _ -> movedWidgets += widgetId },
                    onAddQuickFeature = {},
                    listState = listState,
                    header = { Text("Good morning") },
                    banner = null,
                ) { section -> LiveContent(section) }
            }
        }
        composeRule.waitForIdle()
    }

    /** What Home browses: the real sections, but the time slider, which ticks, as a stand-in. */
    @androidx.compose.runtime.Composable
    private fun LiveContent(section: HomeSection) {
        if (section.type == HomeSectionType.TODAY_COURSES && courses.isEmpty()) {
            Text("live time machine")
            return
        }
        HomeSectionContent(
            section = section,
            allCourses = courses,
            timeSlider = timeSlider,
            quietDays = QuietDays(AcademicCalendar.EMPTY, emptySet()),
            upcomingAssignments = emptyList(),
            assignmentFilter = AssignmentFilter.INCOMPLETE,
            showIgnoredTab = false,
            ignoredAssignmentIds = emptySet(),
            markedCompletedIds = emptySet(),
            courseIgnoredIds = emptySet(),
            isLoggedIn = true,
            isLoading = false,
            initialLoadComplete = true,
            hasUnfinishedAssignment = { false },
            showAbsoluteTime = false,
            invertDirection = false,
            onCourseClick = {},
            onAssignmentClick = {},
            onToggleIgnore = {},
            onMarkCompleted = {},
            onSelectFilter = { filters += it },
            onOpenFeature = {},
            onAddQuickFeature = {},
            onOpenSignInSettings = {},
        )
    }

    private val remove get() = text(R.string.action_remove)

    @Test
    fun `while editing a section shows its outline and none of its content`() {
        show(listOf(today, assignments), isEditing = true)

        // The filter chips above all: they used to switch while editing.
        composeRule.onNodeWithText(text(R.string.assignment_filter_incomplete)).assertDoesNotExist()
        composeRule.onNodeWithText("live time machine").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.home_time_slider_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.home_section_upcoming_assignments)).assertIsDisplayed()
    }

    @Test
    fun `each remove button names the section it removes`() {
        show(listOf(today, assignments, custom(1)), isEditing = true)

        for (title in listOf(text(R.string.home_time_slider_title), text(R.string.home_section_upcoming_assignments), "Section 1")) {
            // The outlines are taller than the screen together.
            composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("$remove $title"))
            composeRule.onNodeWithContentDescription("$remove $title").performClick()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(today.id, assignments.id, "custom-1"), removed)
    }

    @Test
    fun `the drag handle sits above the section's header`() {
        show(listOf(assignments), isEditing = true)

        val handle = composeRule.onNodeWithText(text(R.string.home_section_drag_hint)).fetchSemanticsNode().boundsInRoot
        val header = composeRule.onNodeWithText(text(R.string.home_section_upcoming_assignments)).fetchSemanticsNode().boundsInRoot
        assertTrue("the handle ends at ${handle.bottom}, below the header's top ${header.top}", handle.bottom <= header.top)
    }

    @Test
    fun `the page scrolls while editing to reach a section below the screen`() {
        show((1..8).map(::custom), isEditing = true)

        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Section 8"))
        composeRule.onNodeWithContentDescription("$remove Section 8").assertIsDisplayed()
    }

    @Test
    fun `a swipe that starts on a shortcut scrolls the page and moves nothing`() {
        val shortcuts = quick.copy(
            widgets = listOf(AppFeature.ANNOUNCEMENTS, AppFeature.SCORE, AppFeature.EMPTY_CLASSROOM, AppFeature.CALENDAR)
                .map { WidgetItem("w-${it.id}", it) },
        )
        show(listOf(shortcuts) + (1..5).map(::custom), isEditing = true)

        composeRule.onNodeWithText(text(R.string.feature_score)).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertTrue("the page did not scroll", listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        assertEquals(emptyList<String>(), movedWidgets)
    }

    @Test
    fun `a pull at the top refreshes while browsing`() {
        // The control for the next test: the same pull, outside edit mode.
        show(listOf(assignments, custom(1)), isEditing = false)

        composeRule.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(1, refreshes)
    }

    @Test
    fun `a pull at the top does not refresh while editing`() {
        show(listOf(assignments, custom(1)), isEditing = true)

        composeRule.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(0, refreshes)
    }

    @Test
    fun `a drag keeps its section under the finger with the page scrolled past its header`() {
        show((1..6).map(::custom), isEditing = true)
        // Section 2 first on screen: the list would otherwise follow it by key when it moves.
        composeRule.runOnIdle { listState.requestScrollToItem(2) }
        composeRule.waitForIdle()
        val step = bounds("Section 3").top - bounds("Section 2").top

        // Past the slop, so the drag has started before anything is measured.
        grab("Section 2")
        // Its own header, not a handle: lifted, it overlaps the section it passes.
        val start = bounds("Section 2").top

        // Past half of Section 3, which swaps the two.
        dragBy(step * 0.75f)

        assertEquals(listOf(1, 3, 2, 4, 5, 6).map { "custom-$it" }, sections.value.map { it.id })
        assertEquals(start + step * 0.75f, bounds("Section 2").top, 2f)
        composeRule.onRoot().performTouchInput { up() }
    }

    /**
     * Puts a finger on the handle of [title]'s section and moves it 2px past the slop, down or
     * [up], so the drag has begun.
     */
    private fun grab(title: String, up: Boolean = false) {
        val grip = handleAbove(title)
        composeRule.onRoot().performTouchInput {
            down(grip.center)
            moveBy(Offset(0f, (viewConfiguration.touchSlop + 2f) * if (up) -1 else 1))
        }
        composeRule.waitForIdle()
    }

    private fun dragBy(dy: Float) {
        composeRule.onRoot().performTouchInput { moveBy(Offset(0f, dy)) }
        composeRule.waitForIdle()
    }

    @Test
    fun `a drag just past the swap point does not swap back on the next move`() {
        show((1..4).map(::custom), isEditing = true)
        // A section's height and the gap after it: how far a swap moves the dragged one.
        val step = bounds("Section 3").top - bounds("Section 2").top

        grab("Section 2")
        // The grab has already moved it 2px; this takes it 1px past half of the step.
        dragBy(step / 2 - 1f)
        val swapped = listOf(1, 3, 2, 4).map { "custom-$it" }
        assertEquals(swapped, sections.value.map { it.id })
        // After each move, since one that swapped it back could be undone by the next.
        repeat(3) {
            dragBy(1f)
            assertEquals(swapped, sections.value.map { it.id })
        }
        composeRule.onRoot().performTouchInput { up() }
    }

    @Test
    fun `one drag can pass several sections`() {
        show((1..4).map(::custom), isEditing = true)
        val step = bounds("Section 3").top - bounds("Section 2").top

        // Two places down, both on screen: a section the page has not laid out has no
        // height yet to swap by.
        grab("Section 1")
        dragBy(step * 0.75f)
        dragBy(step)
        composeRule.onRoot().performTouchInput { up() }
        composeRule.waitForIdle()

        assertEquals(listOf(2, 3, 1, 4).map { "custom-$it" }, sections.value.map { it.id })
    }

    @Test
    // Tall enough to measure both sections before scrolling.
    @Config(qualifiers = "+h1300dp")
    fun `a short section dragged up past a tall one partly off screen stays with the finger`() {
        // Six rows of shortcuts, the features repeated: only the height matters here.
        val features = AppFeature.widgetFeatures
        val tall = quick.copy(widgets = (0 until 12).map { WidgetItem("w-$it", features[it % features.size]) })
        show(listOf(tall) + (1..5).map(::custom), isEditing = true)
        val tallTitle = text(R.string.home_section_quick_widgets)
        // Each a section's height and the gap after it.
        val tallStep = bounds("Section 1").top - bounds(tallTitle).top
        val shortStep = bounds("Section 2").top - bounds("Section 1").top
        val slop = android.view.ViewConfiguration.get(composeRule.activity).scaledTouchSlop.toFloat()
        // The tall one first on screen, scrolled up by more than the short one's height and gap,
        // so the short one's place after the swap is past the top of the list, yet little enough
        // that a finger still on the screen can take the short one past the tall one's middle.
        val most = tallStep / 2 + 14f - slop
        assertTrue("no room for the case: $shortStep, $most", shortStep < most)
        composeRule.runOnIdle { listState.requestScrollToItem(1, ((shortStep + most) / 2).toInt()) }
        composeRule.waitForIdle()

        grab("Section 1", up = true)
        val start = bounds("Section 1").top
        dragBy(-(tallStep / 2 - 1f))

        // Its place is now above the top of the list, where the list would drop it, and the drag
        // with it, while the drag still draws it under the finger.
        assertEquals(listOf("custom-1", tall.id), sections.value.map { it.id }.take(2))
        assertEquals(start - (tallStep / 2 - 1f), bounds("Section 1").top, 2f)
        dragBy(-1f)
        assertEquals(start - tallStep / 2, bounds("Section 1").top, 2f)
        composeRule.onRoot().performTouchInput { up() }
    }

    private fun bounds(text: String): Rect = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    /** The drag handle of the resting section titled [title]: the one nearest above its header. */
    private fun handleAbove(title: String): Rect {
        val header = bounds(title)
        return composeRule.onAllNodesWithText(text(R.string.home_section_drag_hint))
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .filter { it.bottom <= header.top }
            .maxBy { it.bottom }
    }

    @Test
    fun `a time picked on the slider is still there after editing`() {
        // Period 3, 10:20 to 11:10, every day, and the clock frozen at 10:40 on a Monday.
        courses = listOf(Course.fromSchedule("CS1001", "Calculus", schedule = (1..7).associateWith { listOf("3") }))
        val tenForty = LocalDate.parse("2026-09-28").atTime(10, 40).atZone(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()
        AppClock.setOverride(ClockOverride(tenForty, frozen = true, savedAtRealMillis = 0L))
        try {
            show(listOf(today), isEditing = false)
            composeRule.onNode(hasContentDescription(text(R.string.home_time_slider_title), substring = true))
                .performTouchInput { swipeLeft() }
            composeRule.waitForIdle()
            // Off "now", which the slider says with its button back to it.
            composeRule.onNodeWithText(text(R.string.home_time_slider_now)).assertIsDisplayed()

            editing.value = true
            composeRule.waitForIdle()
            editing.value = false
            composeRule.waitForIdle()

            composeRule.onNodeWithText(text(R.string.home_time_slider_now)).assertIsDisplayed()
        } finally {
            AppClock.setOverride(null)
        }
    }

    @Test
    fun `a shortcut in a section's outline still removes`() {
        show(listOf(quick), isEditing = true)

        composeRule.onNodeWithContentDescription("$remove ${text(R.string.feature_score)}").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("w-score"), removedWidgets)
    }

    @Test
    fun `a long press on a section starts editing`() {
        show(listOf(assignments), isEditing = false)

        composeRule.onNodeWithText(text(R.string.home_section_upcoming_assignments)).performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertTrue(editing.value)
    }
}
