package org.ntust.app.tigerduck.ui.a11y

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.shared.AccentGradientDirection
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.HueSlider
import org.ntust.app.tigerduck.ui.component.SatValSquare
import org.ntust.app.tigerduck.ui.screen.calendar.CalendarGrid
import org.ntust.app.tigerduck.ui.screen.calendar.dayCellDescription
import org.ntust.app.tigerduck.ui.screen.classtable.SoloCourseCell
import org.ntust.app.tigerduck.ui.screen.settings.AccentColorPage
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * Controls as TalkBack is handed them, read through UiAutomation: each can be
 * focused and activated. A child that clears its own semantics inside a
 * clickable node leaves that node neither clickable nor focusable here, though
 * the semantics tree still holds its click action, so this has to run on a
 * device rather than under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class TalkBackActivatableTest {

    @get:Rule
    val rule = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun node(label: String): AccessibilityNodeInfo {
        rule.waitForIdle()
        // Walked rather than searched: the search by text is not answered for
        // Compose's virtual nodes.
        fun find(info: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            info ?: return null
            if (info.contentDescription?.toString() == label) return info
            for (i in 0 until info.childCount) find(info.getChild(i))?.let { return it }
            return null
        }
        // The test's window may take a moment to become the active one.
        var hit: AccessibilityNodeInfo? = null
        val deadline = System.currentTimeMillis() + 5_000
        while (hit == null && System.currentTimeMillis() < deadline) {
            hit = find(instrumentation.uiAutomation.rootInActiveWindow)
            if (hit == null) Thread.sleep(100)
        }
        assertNotNull("no node reads \"$label\"", hit)
        return hit!!
    }

    private fun assertActivatable(label: String) {
        val info = node(label)
        assertTrue("\"$label\" is not clickable to TalkBack", info.isClickable)
        assertTrue("\"$label\" is not focusable to TalkBack", info.isFocusable)
    }

    @Test
    fun aClassTableTileWithARoomHint() {
        rule.setContent {
            Box(Modifier.size(200.dp)) {
                SoloCourseCell(
                    course = Course(courseNo = "CS1001301", courseName = "Calculus", classroom = "TR-313"),
                    roomHint = "TR-313",
                    spanCount = 2,
                    dayColWidth = 80.dp,
                    cellHeight = 52.dp,
                    x = 0.dp,
                    y = 0.dp,
                    weekday = 1,
                    periodId = "3",
                    hasAssignment = false,
                    assignmentsIgnored = false,
                    onTap = {},
                    onLongPress = {},
                    onRename = {},
                    onPickColor = {},
                    onToggleIgnoreAssignments = null,
                    onDelete = {},
                )
            }
        }

        val label = context.getString(R.string.a11y_timetable_cell)
            .replace("%1\$@", context.getString(R.string.weekday_mon_short))
            .replace("%2\$@", "3")
            .replace("%3\$@", "Calculus, TR-313")
        assertActivatable(label)
    }

    @Test
    fun aCalendarDay() {
        fun day(date: LocalDate) = Date.from(date.atStartOfDay(AppConstants.TAIPEI_ZONE).toInstant())
        rule.setContent {
            CalendarGrid(
                monthDate = day(LocalDate.parse("2026-10-01")),
                selectedDate = day(LocalDate.parse("2026-10-14")),
                eventsByDay = emptyMap(),
                onDateSelected = {},
            )
        }

        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        assertActivatable(
            dayCellDescription(context.getString(R.string.a11y_calendar_day), LocalDate.parse("2026-10-15"), 0, locale)
        )
    }

    @Test
    fun theChosenAccentSwatchReadsAsChosen() {
        rule.setContent {
            AccentColorPage(
                fixedHex = 0x007AFF,
                gradientEnd = null,
                direction = AccentGradientDirection.DEFAULT,
                followsWallpaper = false,
                isDark = false,
                onPick = {},
                onPickGradient = { _, _ -> },
                onPickDirection = {},
                onPickWallpaper = {},
            )
        }

        val info = node(context.getString(R.string.color_name_blue))
        assertTrue("the chosen swatch is not focusable to TalkBack", info.isFocusable)
        @Suppress("DEPRECATION")
        assertTrue("the chosen swatch does not read as checked", info.isChecked)
    }

    @Test
    fun theColorPickersSquareAndHueBarCanBeAdjusted() {
        var hue by mutableFloatStateOf(120f)
        var sat by mutableFloatStateOf(0.5f)
        var value by mutableFloatStateOf(0.5f)
        rule.setContent {
            Column {
                SatValSquare(hue = hue, sat = sat, value = value, onSatValChange = { s, v -> sat = s; value = v })
                HueSlider(hue = hue, onHueChange = { hue = it })
            }
        }

        for (id in listOf(
            R.string.a11y_color_picker_saturation,
            R.string.a11y_color_picker_brightness,
            R.string.a11y_color_picker_hue,
        )) {
            val label = context.getString(id)
            val info = node(label)
            assertTrue("\"$label\" is not focusable to TalkBack", info.isFocusable)
            assertNotNull("\"$label\" has no range for TalkBack to read", info.rangeInfo)
            assertTrue(
                "\"$label\" cannot be swiped up to adjust",
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD in info.actionList,
            )
            assertTrue(
                "\"$label\" cannot be set by TalkBack",
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS in info.actionList,
            )
        }

        // A swipe up, as TalkBack sends it, moves each value a twentieth of its range.
        assertTrue(node(context.getString(R.string.a11y_color_picker_hue)).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        assertTrue(node(context.getString(R.string.a11y_color_picker_brightness)).performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        rule.waitForIdle()
        assertEquals(138f, hue, 0.01f)
        assertEquals(0.45f, value, 0.001f)
        assertEquals(0.5f, sat, 0.001f)
    }
}
