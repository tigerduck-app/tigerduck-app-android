package org.ntust.app.tigerduck.widget

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.runComposition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * The redesigned class widgets drawn on made-up Thursday 2026-11-05 courses, light and in English,
 * as the iPhone's after tigerduck-app#1002: Today's medium and large lists, Week's header and its
 * spoken blocks, Next Class's ranges, the class after it and its countdown, and the redraws of the
 * class hours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetRedesignDrawTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var prefs: AppPreferences

    // Thursday: period 2 (09:10–10:00), 3–4 (10:20–12:10), 6 (13:20–14:10).
    private val early = Course.fromSchedule("ZZ1001", "Early", classroom = "TR-101", schedule = mapOf(4 to listOf("2")))
    private val morning = Course.fromSchedule("ZZ1002", "Morning", classroom = "TR-202", schedule = mapOf(4 to listOf("3", "4")))
    private val afternoon = Course.fromSchedule("ZZ1003", "Afternoon", classroom = "TR-303", schedule = mapOf(4 to listOf("6")))

    @Before
    fun setUp() {
        app.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = AppPreferences(app).apply {
            appLanguage = "en"
            themeMode = "light"
        }
    }

    @After
    fun tearDown() {
        AppClock.setOverride(null)
    }

    private fun state(time: String, courseNameScale: Float = 1f): WidgetState = WidgetDataLoader.state(
        courses = listOf(early, morning, afternoon),
        now = thursday.atTime(LocalTime.parse(time)),
        isLoggedIn = true,
        quietDays = QuietDays(AcademicCalendar.EMPTY, emptySet()),
        changes = CourseChanges.EMPTY,
        courseNameScale = courseNameScale,
    )

    private fun widget(layout: WidgetLayout, state: WidgetState) =
        object : BaseClassTableWidget(layout, preferencesOf = { prefs }, loadState = { _, _ -> state }) {}

    @OptIn(ExperimentalGlanceApi::class)
    private fun drawn(layout: WidgetLayout, state: WidgetState, size: DpSize): List<View> = runBlocking {
        val views: RemoteViews = withTimeout(10_000) {
            widget(layout, state).runComposition(app, sizes = listOf(size)).first { texts(it).isNotEmpty() }
        }
        val out = mutableListOf<View>()
        fun walk(view: View) {
            out += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(views.apply(app, FrameLayout(app)))
        out
    }

    private fun texts(views: RemoteViews): List<String> =
        mutableListOf<String>().also { out ->
            fun walk(view: View) {
                if (view is TextView && view.text.isNotEmpty()) out += view.text.toString()
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(views.apply(app, FrameLayout(app)))
        }

    private fun List<View>.texts(): List<String> = filterIsInstance<TextView>().map { it.text.toString() }.filter { it.isNotEmpty() }

    private fun List<View>.named(text: String): TextView =
        filterIsInstance<TextView>().firstOrNull { it.text.toString() == text } ?: error("no \"$text\" in ${texts()}")

    private val medium = DpSize(320.dp, 150.dp)
    private val large = DpSize(320.dp, 360.dp)

    @Test
    fun `the medium Today widget lists only the classes not over, each by its start`() {
        val views = drawn(WidgetLayout.Today, state("11:00"), medium)

        assertEquals(listOf("Morning", "Afternoon"), views.texts().filter { it in setOf("Early", "Morning", "Afternoon") })
        views.named("10:20")
        assertFalse(views.texts().contains("12:10"))
    }

    @Test
    fun `the Today widget's rows grow with the course name size, so fewer fit`() {
        val names = setOf("Early", "Morning", "Afternoon")
        fun listed(size: DpSize, courseNameScale: Float) =
            drawn(WidgetLayout.Today, state("08:00", courseNameScale), size).texts().filter { it in names }

        assertEquals(listOf("Early", "Morning", "Afternoon"), listed(medium, 1f))
        assertEquals(listOf("Early", "Morning"), listed(medium, 1.6f))
        assertEquals(listOf("Early", "Morning", "Afternoon"), listed(DpSize(320.dp, 220.dp), 1f))
        assertEquals(listOf("Early", "Morning"), listed(DpSize(320.dp, 220.dp), 1.6f))
    }

    @Test
    fun `the medium Today widget says no more classes once the day's are over`() {
        val views = drawn(WidgetLayout.Today, state("15:00"), medium)

        views.named("No more classes today")
    }

    @Test
    fun `the large Today widget lists the whole day by start and end, without periods, the past dimmed`() {
        val views = drawn(WidgetLayout.Today, state("11:00"), large)

        assertEquals(listOf("Early", "Morning", "Afternoon"), views.texts().filter { it in setOf("Early", "Morning", "Afternoon") })
        assertEquals(WidgetTheme.Light.onSurfaceVariant.toArgb(), views.named("Early").currentTextColor)
        assertEquals(WidgetTheme.Light.onSurface.toArgb(), views.named("Morning").currentTextColor)
        views.named("10:20")
        views.named("12:10")
        assertTrue(views.texts().none { it == "3–4" || it == "2" })
        assertEquals("10:20 to 12:10", views.filter { it.contentDescription?.toString() == "10:20 to 12:10" }.single().contentDescription)
    }

    @Test
    fun `a tall Today widget lists every row that fits, past Glance's ten children to a column`() {
        val day = listOf("1", "2", "3", "4", "6", "7", "8").map { period ->
            Course.fromSchedule("ZZ20$period", "Class $period", schedule = mapOf(4 to listOf(period)))
        }
        val state = WidgetDataLoader.state(
            courses = day,
            now = thursday.atTime(7, 0),
            isLoggedIn = true,
            quietDays = QuietDays(AcademicCalendar.EMPTY, emptySet()),
            changes = CourseChanges.EMPTY,
            courseNameScale = 1f,
        )

        val views = drawn(WidgetLayout.Today, state, DpSize(320.dp, 420.dp))

        day.forEach { views.named(it.courseName) }
    }

    @Test
    fun `the Week widget writes the term's week and reads each block with its time and room`() {
        val calendar = AcademicCalendar(1L, listOf(SemesterTerm("1151", LocalDate.parse("2026-09-07"), LocalDate.parse("2027-01-08"))), emptyList())
        val state = state("11:00").copy(termWeek = calendar.week(of = "1151", on = thursday))

        val views = drawn(WidgetLayout.Week, state, DpSize(320.dp, 360.dp))

        assertEquals("Week 9", views.named("W9").contentDescription)
        assertTrue(views.any { it.contentDescription?.toString() == "Morning, 10:20 to 12:10, TR-202" })
    }

    @Test
    fun `the Week widget dates its days on a large widget, the week running from today`() {
        val large = drawn(WidgetLayout.Week, state("11:00"), DpSize(400.dp, 520.dp))
        val small = drawn(WidgetLayout.Week, state("11:00"), DpSize(320.dp, 360.dp))

        // Thursday the 5th to Wednesday the 11th, which no period is named.
        large.named("11")
        assertFalse(small.texts().contains("11"))
    }

    @Test
    fun `Next Class shows the next class's range, a countdown, and the class after it where there is room`() {
        val views = drawn(WidgetLayout.NextClass, state("10:00"), DpSize(320.dp, 200.dp))

        views.named("10:20–12:10")
        views.named("in 20 min.")
        views.named("Afternoon")
        views.named("13:20–14:10")
    }

    @Test
    fun `Next Class shows the class in session with its range, and the one after it`() {
        val views = drawn(WidgetLayout.NextClass, state("11:00"), DpSize(320.dp, 200.dp))

        views.named("Morning")
        views.named("10:20–12:10")
        views.named("Afternoon")
    }

    @Test
    fun `Next Class shows the class after it only where the card fits over it, the card's name on one line then`() {
        val roomy = drawn(WidgetLayout.NextClass, state("11:00"), DpSize(320.dp, 170.dp))
        roomy.named("Afternoon")
        assertEquals(1, roomy.named("Morning").maxLines)
        assertEquals(2, drawn(WidgetLayout.NextClass, state("11:00"), DpSize(320.dp, 150.dp)).named("Morning").maxLines)

        // Too short for the class in session's card and the row under it, or for both at the largest course names.
        assertFalse(drawn(WidgetLayout.NextClass, state("11:00"), DpSize(320.dp, 150.dp)).texts().contains("Afternoon"))
        assertFalse(drawn(WidgetLayout.NextClass, state("11:00", courseNameScale = 1.6f), DpSize(320.dp, 170.dp)).texts().contains("Afternoon"))
        // Too narrow for the row's range and some of its name: at 110dp the range takes the row's whole width.
        assertFalse(drawn(WidgetLayout.NextClass, state("11:00"), DpSize(110.dp, 200.dp)).texts().contains("Afternoon"))
    }

    @Test
    fun `the compact Next Class widget gives a later day's class its range too`() {
        // After Thursday's last class, the next is the Thursday after's first.
        val views = drawn(WidgetLayout.NextClass, state("15:00"), DpSize(320.dp, 80.dp))

        views.named("Early")
        views.named("Thu 09:10–10:00")
    }

    @Test
    fun `the class hours redraw every five minutes while class widgets are placed, never waking the phone`() {
        val now = thursday.atTime(11, 2).atZone(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()
        AppClock.setOverride(ClockOverride(now, frozen = true, savedAtRealMillis = 0L))
        val calendar = AcademicCalendarStore(app, OkHttpClient(), prefs)
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))

        WidgetBoundaryScheduler(app, calendar).scheduleForToday(listOf(morning), ticking = false)
        assertEquals(1, alarms.scheduledAlarms.size)

        WidgetBoundaryScheduler(app, calendar).scheduleForToday(listOf(morning), ticking = true)
        val tick = alarms.scheduledAlarms.single { it.type == AlarmManager.RTC }
        assertEquals(3.0, (tick.triggerAtTime - System.currentTimeMillis()) / 60_000.0, 1.0)
    }

    @Test
    fun `the phone's font size is capped for the widgets' type`() {
        assertEquals(1f, WidgetScale().capped(1f).fontCap, 0.001f)
        assertEquals(1f, WidgetScale().capped(1.3f).fontCap, 0.001f)
        assertEquals(1.3f / 2f, WidgetScale().capped(2f).fontCap, 0.001f)
        assertEquals(1.3f, WidgetScale.cappedFontScale(2f), 0.001f)
    }
}
