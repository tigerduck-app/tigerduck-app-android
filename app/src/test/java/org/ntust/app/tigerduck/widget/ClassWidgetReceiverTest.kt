package org.ntust.app.tigerduck.widget

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.internal.GeneratedComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.analytics.testAnalyticsLogger
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.notification.EntryPointApplication
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.util.testSignIn
import org.ntust.app.tigerduck.widget.receivers.TodayLightWidgetReceiver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/**
 * A class widget's update, which the system sends as the widget is placed, arms the class
 * boundaries and the class hours' redraws, so a widget placed after the app last armed them ticks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EntryPointApplication::class)
class ClassWidgetReceiverTest {

    private val app = RuntimeEnvironment.getApplication() as EntryPointApplication
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
    fun `every class widget's receiver arms the class hours, and the library's does not`() {
        val receivers = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_RECEIVERS or PackageManager.GET_META_DATA)
            .receivers.orEmpty()
            .filter { it.metaData?.containsKey(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER) == true }
            .map { Class.forName(it.name).getDeclaredConstructor().newInstance() as GlanceAppWidgetReceiver }

        receivers.forEach {
            assertEquals(it.javaClass.name, it.glanceAppWidget is BaseClassTableWidget, it is ClassWidgetReceiver)
        }
        assertEquals(6, receivers.count { it is ClassWidgetReceiver })
    }

    @Test
    fun `placing a class widget arms the next class boundary and the class hours' redraws, and sends nothing`() = runBlocking {
        val calendar = AcademicCalendarStore(app, OkHttpClient(), prefs)
        val cache = DataCache(app, calendar)
        // Monday 10:20.
        cache.saveCourses(listOf(Course.fromSchedule("CS1001301", "Calculus", schedule = mapOf(1 to listOf("3")))), testSignIn())
        val updater = WidgetUpdater(
            context = app,
            dataCache = cache,
            boundaryScheduler = WidgetBoundaryScheduler(app, calendar),
            analyticsLogger = testAnalyticsLogger(app),
            scope = scope,
            uiLanguage = UiLanguageMonitor(emptyFlow(), Build.VERSION_CODES.R),
        )
        app.component = object : ClassWidgetEntryPoint, WidgetThemeEntryPoint, GeneratedComponent {
            override fun widgetUpdater() = updater
            override fun appPreferences() = prefs
        }
        // A Today widget placed after the app armed nothing: the system binds it and sends its update.
        val today = ComponentName(app, TodayLightWidgetReceiver::class.java)
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(41, AppWidgetProviderInfo().apply { provider = today })
        val realNow = System.currentTimeMillis()

        app.sendBroadcast(
            Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setComponent(today)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(41)),
        )
        shadowOf(Looper.getMainLooper()).idle()

        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        val deadline = System.currentTimeMillis() + 5_000
        while (alarms.scheduledAlarms.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val boundary = alarms.scheduledAlarms.single { it.type == AlarmManager.RTC_WAKEUP }
        assertEquals(200.0, (boundary.triggerAtTime - realNow) / 60_000.0, 1.0)
        alarms.scheduledAlarms.single { it.type == AlarmManager.RTC }
        // Arming redraws nothing, so no update goes out to come back here.
        assertEquals(1, shadowOf(app).broadcastIntents.count { it.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE })
    }
}
