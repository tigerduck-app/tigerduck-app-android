package org.ntust.app.tigerduck.data.preferences

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiLanguageMonitorTest {

    private val settingChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A monitor at [sdkInt], already told the launch locales, and a count of what it fires. */
    private fun TestScope.monitorAt(sdkInt: Int): Pair<UiLanguageMonitor, () -> Int> {
        val monitor = UiLanguageMonitor(settingChanged, sdkInt)
        monitor.onLocales("zh-TW")
        var fired = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            monitor.changes.collect { fired++ }
        }
        return monitor to { fired }
    }

    // --- below API 33: the setting is a signal of its own -------------------

    @Test
    fun `below 33 an in-app switch fires on the setting alone`() = runTest {
        // AppCompat applies it to Activities only: no configuration change
        // ever reaches the Application, so the setting is all there is.
        val (_, fired) = monitorAt(sdkInt = 32)
        settingChanged.emit(Unit)
        assertEquals(1, fired())
    }

    @Test
    fun `below 33 a phone language change under follow system fires`() = runTest {
        val (monitor, fired) = monitorAt(sdkInt = 32)
        monitor.onLocales("en-US")
        assertEquals(1, fired())
    }

    // --- API 33 and up: only once the switch has reached the process --------

    @Test
    fun `from 33 the setting alone does not fire`() = runTest {
        // It changes before the new language reaches the application context;
        // acting on it then reads "Follow system" as the language just left.
        val (_, fired) = monitorAt(sdkInt = 33)
        settingChanged.emit(Unit)
        assertEquals(0, fired())
    }

    @Test
    fun `from 33 an in-app switch fires once, when the locales arrive`() = runTest {
        val (monitor, fired) = monitorAt(sdkInt = 33)
        settingChanged.emit(Unit)
        monitor.onLocales("en-US")
        assertEquals(1, fired())
    }

    @Test
    fun `from 33 a phone language change under follow system fires`() = runTest {
        val (monitor, fired) = monitorAt(sdkInt = 36)
        monitor.onLocales("ja-JP,zh-TW")
        assertEquals(1, fired())
    }

    // --- configuration changes that are not about language ------------------

    @Test
    fun `the launch report does not fire`() = runTest {
        val (_, fired) = monitorAt(sdkInt = 33)
        assertEquals(0, fired())
    }

    @Test
    fun `the same locales again, as on rotation or a theme change, do not fire`() = runTest {
        val (monitor, fired) = monitorAt(sdkInt = 33)
        monitor.onLocales("zh-TW")
        monitor.onLocales("zh-TW")
        assertEquals(0, fired())
    }

    @Test
    fun `switching away and back fires each time`() = runTest {
        val (monitor, fired) = monitorAt(sdkInt = 33)
        monitor.onLocales("en-US")
        monitor.onLocales("zh-TW")
        assertEquals(2, fired())
    }
}
