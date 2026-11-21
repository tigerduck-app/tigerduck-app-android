package org.ntust.app.tigerduck.widget

import android.app.AlarmManager
import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.runComposition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/**
 * The class table choice as the system sees it: which widgets offer the configuration, which
 * table each widget asks the loader for, and the redraw a change to the imported people brings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetClassTableWiringTest {

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

    private val providers: List<ActivityInfo> =
        app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_RECEIVERS or PackageManager.GET_META_DATA)
            .receivers
            .orEmpty()
            .filter { it.metaData?.containsKey(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER) == true }

    /** The provider's configuration activity and its widget features. */
    private fun ActivityInfo.configuration(): Pair<String?, Int> {
        val xml = app.resources.getXml(metaData.getInt(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER))
        while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { /* skip to the root element */ }
        return xml.getAttributeValue(ANDROID_NS, "configure") to xml.getAttributeIntValue(ANDROID_NS, "widgetFeatures", 0)
    }

    @Test
    fun `Today and Week offer the choice once placed, and Next Class and the library do not`() {
        val optional = AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE or
            AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL
        providers.forEach { provider ->
            val receiver = Class.forName(provider.name).getDeclaredConstructor().newInstance() as GlanceAppWidgetReceiver
            val showsChoice = (receiver.glanceAppWidget as? BaseClassTableWidget)?.showsChosenTable == true
            val (configure, features) = provider.configuration()

            assertEquals(provider.name, provider.name in WidgetClassTable.receivers, showsChoice)
            if (showsChoice) {
                assertEquals(provider.name, WidgetClassTableActivity::class.java.name, configure)
                assertEquals(provider.name, optional, features and optional)
            } else {
                assertNull(provider.name, configure)
            }
        }
        assertEquals(4, WidgetClassTable.receivers.size)
    }

    @Test
    fun `the configuration takes only this app's Today and Week widgets`() {
        val today = ComponentName(app, WidgetClassTable.receivers.first())

        assertTrue(WidgetClassTable.takesChoice(today, app.packageName))
        assertFalse(WidgetClassTable.takesChoice(ComponentName("org.example.other", today.className), app.packageName))
        assertFalse(
            WidgetClassTable.takesChoice(
                ComponentName(app, "org.ntust.app.tigerduck.widget.receivers.NextClassLightWidgetReceiver"),
                app.packageName,
            ),
        )
        assertFalse(WidgetClassTable.takesChoice(null, app.packageName))
    }

    /** The person [layout]'s widget asks the loader for when its state says it is set to "ming". */
    @OptIn(ExperimentalGlanceApi::class)
    private fun personAskedFor(layout: WidgetLayout): String? = runBlocking {
        val asked = CompletableDeferred<String?>()
        val widget = object : BaseClassTableWidget(
            layout,
            SizeMode.Single,
            preferencesOf = { prefs },
            loadState = { _, personId ->
                asked.complete(personId)
                emptyState()
            },
        ) {}
        val drawing = launch(Dispatchers.Default) {
            widget.runComposition(app, state = preferencesOf(WidgetClassTable.PersonKey to "ming")).collect {}
        }
        try {
            withTimeout(10_000) { asked.await() }
        } finally {
            drawing.cancel()
        }
    }

    @Test
    fun `Today and Week load the table they were set to, and Next Class the user's own`() {
        assertEquals("ming", personAskedFor(WidgetLayout.Today))
        assertEquals("ming", personAskedFor(WidgetLayout.Week))
        assertNull(personAskedFor(WidgetLayout.NextClass))
    }

    @Test
    fun `a change to the imported people redraws the widgets`() = runBlocking {
        val calendar = AcademicCalendarStore(app, OkHttpClient(), prefs)
        val cache = DataCache(app, calendar)
        cache.saveCourses(listOf(Course.fromSchedule("CS1001301", "Calculus", schedule = mapOf(1 to listOf("3")))), testSignIn())
        val store = ImportedClassTableStore({ ImportedClassTableStore.fileIn(app) }, { null }, Dispatchers.IO)
        WidgetUpdater(
            context = app,
            dataCache = cache,
            boundaryScheduler = WidgetBoundaryScheduler(app, calendar),
            analyticsLogger = testAnalyticsLogger(app),
            scope = scope,
            uiLanguage = UiLanguageMonitor(emptyFlow(), Build.VERSION_CODES.R),
            importedClassTables = store,
        )
        // A redraw ends by arming the alarm for the next class boundary, the
        // one trace it leaves with no widget placed in this test.
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
        Thread.sleep(500)
        assertEquals(0, alarms.scheduledAlarms.size)

        val code = ClassTableShareCode(
            ClassTableShareCode.Format.TIGER_DUCK, "B11300001", null, listOf(ClassTableShareCode.Term("1151", listOf("ZZ1001301"))),
        )
        store.add(code, "Ming", emptyMap())

        val deadline = System.currentTimeMillis() + 5_000
        while (alarms.scheduledAlarms.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun `after a reboot the boundaries of a table a placed widget shows are armed too`() = runBlocking {
        val calendar = AcademicCalendarStore(app, OkHttpClient(), prefs)
        val cache = DataCache(app, calendar)
        // The user's class is Monday 10:20; Ming's, shown on a placed Today widget, 08:10.
        cache.saveCourses(listOf(Course.fromSchedule("CS1001301", "Calculus", schedule = mapOf(1 to listOf("3")))), testSignIn())
        val semester = cache.currentSemesterCode()
        val store = ImportedClassTableStore({ ImportedClassTableStore.fileIn(app) }, { null }, Dispatchers.IO)
        val code = ClassTableShareCode(
            ClassTableShareCode.Format.TIGER_DUCK, "B11300001", null, listOf(ClassTableShareCode.Term(semester, listOf("ZZ1001301"))),
        )
        val ming = store.add(
            code, "Ming", mapOf(semester to listOf(Course.fromSchedule("ZZ1001301", "Early", schedule = mapOf(1 to listOf("1"))))),
        )!!
        val today = AppWidgetProviderInfo().apply { provider = ComponentName(app, WidgetClassTable.receivers.first()) }
        shadowOf(AppWidgetManager.getInstance(app)).addBoundWidget(41, today)
        updateAppWidgetState(app, GlanceAppWidgetManager(app).getGlanceIdBy(41)) { it[WidgetClassTable.PersonKey] = ming.id }
        val updater = WidgetUpdater(
            context = app,
            dataCache = cache,
            boundaryScheduler = WidgetBoundaryScheduler(app, calendar),
            analyticsLogger = testAnalyticsLogger(app),
            scope = scope,
            uiLanguage = UiLanguageMonitor(emptyFlow(), Build.VERSION_CODES.R),
            importedClassTables = store,
        )
        val realNow = System.currentTimeMillis()

        updater.armBoundaries()

        val alarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.single()
        assertEquals(70.0, (alarm.triggerAtTime - realNow) / 60_000.0, 1.0)
    }

    private fun emptyState() = WidgetState(
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

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
