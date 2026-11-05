package org.ntust.app.tigerduck.ui.screen.calendar

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.session.Account
import org.ntust.app.tigerduck.auth.session.AccountEvent
import org.ntust.app.tigerduck.auth.session.AccountSessions
import org.ntust.app.tigerduck.auth.session.SignOutReason
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.screen.announcements.testSessions
import org.ntust.app.tigerduck.util.bare
import org.ntust.app.tigerduck.util.set
import org.ntust.app.tigerduck.util.testSignIn
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

/** The calendar drops what the account that left has on it, however soon the next signs in. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CalendarFollowsTheAccountTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var cache: DataCache
    private lateinit var sessions: AccountSessions
    private lateinit var calendar: CalendarViewModel

    // Signed out as far as the page's fetch can tell: no fetch reaches the network in this test.
    private val auth = bare<AuthService>().also { it.set("authState", MutableStateFlow(false)) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPreferences(context)
        val academic = AcademicCalendarStore(context, OkHttpClient(), prefs)
        cache = DataCache(context, academic)
        sessions = testSessions(context)
        sessions.school.signedInFrom(MutableStateFlow(true))
        calendar = CalendarViewModel(
            bare(), bare(), bare(), auth, cache, prefs, UiLanguageMonitor(prefs), context, academic,
            bare(), bare(), bare(), bare(), sessions,
        )
    }

    @After
    fun tearDown() {
        calendar.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun titlesWhenLoaded(): List<String> {
        repeat(100) {
            dispatcher.scheduler.runCurrent()
            val titles = calendar.events.value.map { it.title }
            if (titles.isNotEmpty()) return titles
            Thread.sleep(20)
        }
        return calendar.events.value.map { it.title }
    }

    @Test
    fun `a sign-out and a sign-in landing before it runs are both seen`() {
        runBlocking {
            cache.saveCalendarEvents(
                listOf(CalendarEvent("e1", "The student's exam", Date(AppClock.nowMillis()), "school")),
                testSignIn(),
            )
        }
        calendar.load()
        assertEquals(listOf("The student's exam"), titlesWhenLoaded())
        runBlocking { cache.clearAllUserData() }

        // Both before the page next runs: the state alone reads as no change.
        sessions.emit(AccountEvent.SignedOut(Account.SCHOOL, SignOutReason.SCHOOL, sessions.school.signIns.current, null))
        sessions.emit(AccountEvent.SignedIn(Account.SCHOOL, byHand = false))
        repeat(20) {
            dispatcher.scheduler.runCurrent()
            Thread.sleep(10)
        }

        assertEquals(emptyList<String>(), calendar.events.value.map { it.title })
    }
}
