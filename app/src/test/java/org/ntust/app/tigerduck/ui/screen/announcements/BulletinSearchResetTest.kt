package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.util.AppReturns
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The bulletin search clears once the app comes back from more than five minutes away -- also
 * when it went away from a bulletin's own page, where the list screen is not on screen to see it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BulletinSearchResetTest {
    @get:Rule val main = MainDispatcherRule()

    private var clock = 0L
    private lateinit var observer: DefaultLifecycleObserver
    private lateinit var vm: AnnouncementsViewModel

    private val owner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = throw UnsupportedOperationException()
    }

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPreferences(context)
        val tokens = V3TokenStore(context.getSharedPreferences("tokens", Context.MODE_PRIVATE))
        val api = BulletinApiClient(OkHttpClient(), prefs, AuthTokenManager(tokens, OkHttpClient(), prefs, "device-1"))
        val returns = AppReturns(now = { clock }, register = { observer = it as DefaultLifecycleObserver })
        val cache = BulletinCache(context)
        vm = AnnouncementsViewModel(
            SavedStateHandle(), api, cache, BulletinRepository(), BulletinReadStateStore(context),
            testDemoAccount(context, prefs, cache), testCredentials(context), returns,
        )
    }

    private fun away(ms: Long) {
        observer.onStop(owner)
        clock += ms
        observer.onStart(owner)
    }

    @Test
    fun `a search left for more than five minutes in the background is cleared on return`() {
        vm.setSearch("停課")
        away(BULLETIN_SEARCH_RESET_MS + 1)
        assertEquals("", vm.state.value.searchText)
        assertEquals(1, vm.searchResets.value)
    }

    @Test
    fun `a quick trip to another app keeps the search`() {
        vm.setSearch("停課")
        away(BULLETIN_SEARCH_RESET_MS)
        assertEquals("停課", vm.state.value.searchText)
        assertEquals(0, vm.searchResets.value)
    }

    @Test
    fun `with no search, coming back clears nothing`() {
        away(BULLETIN_SEARCH_RESET_MS * 3)
        assertEquals(0, vm.searchResets.value)
    }
}
