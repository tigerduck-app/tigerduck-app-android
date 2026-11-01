package org.ntust.app.tigerduck.widget

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.runComposition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.ntust.app.tigerduck.analytics.testAnalyticsLogger
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.changes
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.row
import org.ntust.app.tigerduck.shared.timeline.CourseChangeFixture.thursday
import org.ntust.app.tigerduck.shared.timeline.CourseChanges
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.ui.component.CourseChangeInkLight
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalTime

/**
 * The class widgets drawn under the school's course changes, in a light theme and English, and
 * redrawn when a new feed comes. Thursday 2026-11-05 is the fixture's day; course numbers and
 * rooms are made up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetCourseChangesDrawTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: AppPreferences

    private val gone = Course.fromSchedule("CC901", "Gone", classroom = "T1-101", schedule = mapOf(4 to listOf("2", "3")))
    private val moved = Course.fromSchedule("CC902", "Moved", classroom = "T1-102", schedule = mapOf(4 to listOf("6")))
    private val feed = changes(
        row(1, "cancelled", courseNo = "CC901"),
        row(2, "room", values = mapOf("room" to "T9-909"), courseNo = "CC902"),
        row(3, "start", values = mapOf("start" to "13:40"), courseNo = "CC902"),
    )

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
        scope.cancel()
        AppClock.setOverride(null)
    }

    private fun state(time: String, changes: CourseChanges = feed): WidgetState = WidgetDataLoader.state(
        courses = listOf(gone, moved),
        now = thursday.atTime(LocalTime.parse(time)),
        isLoggedIn = true,
        quietDays = QuietDays(AcademicCalendar.EMPTY, emptySet()),
        changes = changes,
        courseNameScale = 1f,
    )

    private fun widget(layout: WidgetLayout, state: WidgetState) = object : BaseClassTableWidget(
        layout,
        if (layout == WidgetLayout.Today) SizeMode.Single else SizeMode.Exact,
        preferencesOf = { prefs },
        loadState = { state },
    ) {}

    @OptIn(ExperimentalGlanceApi::class)
    private fun texts(widget: GlanceAppWidget): List<TextView> = runBlocking {
        val views: RemoteViews = withTimeout(10_000) {
            widget.runComposition(app, sizes = listOf(DpSize(300.dp, 300.dp)))
                .first { textsIn(it).isNotEmpty() }
        }
        textsIn(views)
    }

    private fun textsIn(views: RemoteViews): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(view: View) {
            if (view is TextView && view.text.isNotEmpty()) out += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(views.apply(app, FrameLayout(app)))
        return out
    }

    private fun List<TextView>.named(text: String): TextView =
        firstOrNull { it.text.toString() == text } ?: error("no \"$text\" in ${map { it.text.toString() }}")

    private fun TextView.isStruckThrough(): Boolean =
        (text as? Spanned)?.getSpans(0, text.length, StrikethroughSpan::class.java)?.isNotEmpty() == true

    private val ink = CourseChangeInkLight.toArgb()

    @Test
    fun `the Today widget strikes a cancelled class through and marks a moved one in ink`() {
        val texts = texts(widget(WidgetLayout.Today, state("08:00")))

        assertTrue(texts.named("Gone").isStruckThrough())
        assertEquals(WidgetTheme.Light.onSurfaceVariant.toArgb(), texts.named("Gone").currentTextColor)
        texts.named("Cancelled")
        assertFalse(texts.named("Moved").isStruckThrough())
        assertEquals(ink, texts.named("⇄ T9-909").currentTextColor)
        assertEquals(ink, texts.named("13:40–14:10").currentTextColor)
        assertTrue(texts.none { it.text.toString() == "T1-101" })
    }

    @Test
    fun `the Next class widget marks a changed start and room in ink`() {
        val texts = texts(widget(WidgetLayout.NextClass, state("12:00")))

        texts.named("Moved")
        assertEquals(ink, texts.named("13:40").currentTextColor)
        assertEquals(ink, texts.named("⇄ T9-909").currentTextColor)
    }

    @Test
    fun `the Next class widget marks the changed time of a class in session in ink`() {
        val texts = texts(widget(WidgetLayout.NextClass, state("13:45")))

        assertEquals(ink, texts.named("13:40–14:10  6").currentTextColor)
        assertEquals(ink, texts.named("⇄ T9-909").currentTextColor)
    }

    @Test
    fun `the Week widget strikes a cancelled block through and names its cancellation`() {
        val texts = texts(widget(WidgetLayout.Week, state("08:00")))

        assertTrue(texts.named("Gone").isStruckThrough())
        texts.named("Cancelled")
        assertFalse(texts.named("Moved").isStruckThrough())
    }

    @Test
    fun `with no feed the widgets draw no mark of a change`() {
        for (layout in WidgetLayout.entries) {
            val texts = texts(widget(layout, state("12:00", CourseChanges.EMPTY)))
            assertTrue("$layout", texts.none { it.isStruckThrough() || it.text.startsWith("⇄") || it.text.toString() == "Cancelled" })
        }
    }

    @Test
    fun `a new feed redraws the widgets`() = runBlocking {
        // A Thursday morning, before the class below.
        val morning = thursday.atTime(7, 0).atZone(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()
        AppClock.setOverride(ClockOverride(morning, frozen = true, savedAtRealMillis = 0L))
        val calendar = AcademicCalendarStore(app, OkHttpClient(), prefs)
        val cache = DataCache(app, calendar)
        cache.saveCourses(listOf(moved))
        val feeds = MutableStateFlow(CourseChanges.EMPTY)
        WidgetUpdater(
            context = app,
            dataCache = cache,
            boundaryScheduler = WidgetBoundaryScheduler(app, calendar),
            analyticsLogger = testAnalyticsLogger(app),
            scope = scope,
            uiLanguage = UiLanguageMonitor(MutableSharedFlow<Unit>(), Build.VERSION_CODES.R),
            courseChangesUpdated = feeds,
        )
        // A redraw ends by arming the alarm for the next class boundary, the
        // one trace it leaves with no widget placed in this test.
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        Thread.sleep(500)
        assertEquals(0, alarms.scheduledAlarms.size)

        feeds.value = feed

        val deadline = System.currentTimeMillis() + 5_000
        while (alarms.scheduledAlarms.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun `the day's refresh comes at a class's changed start`() {
        // Thursday 13:25: the class's timetable start has passed, but the school moved it to 13:40.
        val now = thursday.atTime(13, 25).atZone(AppConstants.TAIPEI_ZONE).toInstant().toEpochMilli()
        AppClock.setOverride(ClockOverride(now, frozen = true, savedAtRealMillis = 0L))
        val calendar = AcademicCalendarStore(app, OkHttpClient(), prefs)
        val realNow = System.currentTimeMillis()

        WidgetBoundaryScheduler(app, calendar, courseChanges = { feed }).scheduleForToday(listOf(moved))

        val alarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        val minutesAhead = (alarm.triggerAtTime - realNow) / 60_000.0
        assertEquals(15.0, minutesAhead, 1.0)
    }
}
