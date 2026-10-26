package org.ntust.app.tigerduck.widget

import android.app.AlarmManager
import android.app.Application
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.runComposition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.analytics.testAnalyticsLogger
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/**
 * The widgets in the app's own language: redrawn when it changes, and drawn
 * in it below API 33, where an in-app language reaches Activities only. The
 * phone here is set to Traditional Chinese and the app to English. Nothing
 * here sets the app's locales, so the application's context is the phone's on
 * the default SDK too, as below 33, and the monitor is told the level itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "zh-rTW")
class WidgetLanguageTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = AppPreferences(app).apply { appLanguage = "en" }

    @Before
    fun freezeClock() {
        // A Monday morning, before the class below.
        val monday = ZonedDateTime.of(2026, 10, 12, 7, 0, 0, 0, AppConstants.TAIPEI_ZONE)
        AppClock.setOverride(ClockOverride(monday.toInstant().toEpochMilli(), frozen = true, savedAtRealMillis = 0L))
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppClock.setOverride(null)
    }

    @Test
    fun `a change of language redraws the widgets`() = runBlocking {
        val calendar = AcademicCalendarStore(app, OkHttpClient(), prefs)
        val cache = DataCache(app, calendar)
        cache.saveCourses(listOf(Course.fromSchedule("CS1001301", "Calculus", schedule = mapOf(1 to listOf("3")))))
        val settingChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        WidgetUpdater(
            context = app,
            dataCache = cache,
            boundaryScheduler = WidgetBoundaryScheduler(app, calendar),
            analyticsLogger = testAnalyticsLogger(app),
            scope = scope,
            uiLanguage = UiLanguageMonitor(settingChanged, Build.VERSION_CODES.R),
        )
        // A redraw ends by arming the alarm for the next class boundary, the
        // one trace it leaves with no widget placed in this test.
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        Thread.sleep(500)
        assertEquals(0, alarms.scheduledAlarms.size)

        settingChanged.emit(Unit)

        val deadline = System.currentTimeMillis() + 5_000
        while (alarms.scheduledAlarms.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @OptIn(ExperimentalGlanceApi::class)
    private fun textsOf(widget: GlanceAppWidget): List<String> = runBlocking {
        val views: RemoteViews = withTimeout(10_000) {
            widget.runComposition(app).first { textsIn(it).isNotEmpty() }
        }
        textsIn(views)
    }

    private fun textsIn(views: RemoteViews): List<String> {
        val root = views.apply(app, FrameLayout(app))
        val out = mutableListOf<String>()
        fun walk(view: View) {
            if (view is TextView && view.text.isNotEmpty()) out += view.text.toString()
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun `a class widget draws in the app's language`() {
        val today = object : BaseClassTableWidget(
            WidgetLayout.Today,
            SizeMode.Single,
            preferencesOf = { prefs },
            loadState = {
                WidgetState(
                    courses = emptyList(),
                    activeWeekdays = (1..5).toList(),
                    activePeriodIds = emptyList(),
                    currentWeekday = 1,
                    currentMinuteOfDay = 7 * 60,
                    isLoggedIn = true,
                    isQuietToday = false,
                    ongoingBlocks = emptyList(),
                    nextTodayBlock = null,
                    tomorrowFirstCourseNo = null,
                    tomorrowFirstCourseWeekday = null,
                    tomorrowFirstCoursePeriodId = null,
                    courseColors = emptyMap(),
                    courseNameScale = 1f,
                )
            },
        ) {}

        val texts = textsOf(today)

        assertTrue("$texts", "No classes today" in texts)
    }

    @Test
    fun `the library shortcut draws in the app's language`() {
        val texts = textsOf(LibraryShortcutWidget(preferencesOf = { prefs }))

        assertTrue("$texts", "Library" in texts)
    }
}
