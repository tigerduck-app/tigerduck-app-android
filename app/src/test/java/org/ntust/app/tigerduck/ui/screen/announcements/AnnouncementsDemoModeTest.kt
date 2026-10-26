package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.ui.screen.announcements.AnnouncementsViewModel.LoadState
import org.ntust.app.tigerduck.util.AppReturns
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The demo account's bulletins are the ones its sign-in cached, and demo mode refuses every
 * request: the page shows them as loaded rather than trying, and failing, to refresh them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnnouncementsDemoModeTest {

    @get:Rule val main = MainDispatcherRule()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
    private val cache = BulletinCache(context)
    private val fixtures = DebugFixtureStore(context).apply { demoMode = true }

    /** List fetches that set out, counted before demo mode refuses them, as on the device. */
    private val listRequests = AtomicInteger()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("/bulletins")) listRequests.incrementAndGet()
            chain.proceed(chain.request())
        }
        .addInterceptor(DemoModeInterceptor(fixtures))
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Unavailable")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun viewModel(): AnnouncementsViewModel {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        val api = BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
        return AnnouncementsViewModel(
            SavedStateHandle(), api, cache, BulletinRepository(), BulletinReadStateStore(context),
            testDemoAccount(context, prefs, cache, fixtures), testCredentials(context), AppReturns(now = { 0L }, register = {}),
        )
    }

    /** The list's IO runs on real threads, so this waits for it rather than for a test clock. */
    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "never: $what" }
            Thread.sleep(10)
        }
    }

    private fun settled(vm: AnnouncementsViewModel) =
        vm.state.value.loadState.let { it is LoadState.Loaded || it is LoadState.Failed }

    @Test
    fun `the first visit shows the demo bulletins as loaded, without a fetch`() {
        runBlocking { cache.save(listOf(bulletin(1), bulletin(2))) }
        val vm = viewModel()
        vm.load()
        eventually("the page settles") { settled(vm) }

        assertEquals(LoadState.Loaded, vm.state.value.loadState)
        assertEquals(listOf(2, 1), vm.state.value.displayed.map { it.id })
        assertFalse("no page behind the demo list", vm.state.value.hasMore)
        assertEquals(0, listRequests.get())
    }

    @Test
    fun `a pull to refresh keeps the demo bulletins loaded, without a fetch`() {
        runBlocking { cache.save(listOf(bulletin(1))) }
        val vm = viewModel()
        vm.load()
        eventually("the page settles") { settled(vm) }

        vm.refresh()

        assertEquals(LoadState.Loaded, vm.state.value.loadState)
        assertEquals(listOf(1), vm.state.value.displayed.map { it.id })
        assertEquals(0, listRequests.get())
    }

    @Test
    fun `a demo account with no bulletins shows the empty list, not a failure`() {
        val vm = viewModel()
        vm.load()
        eventually("the page settles") { settled(vm) }

        assertEquals(LoadState.Loaded, vm.state.value.loadState)
        assertEquals(0, listRequests.get())
    }

    private fun bulletin(id: Int) = BulletinSummary(
        id = id, externalId = "e$id", title = "Bulletin $id", titleClean = null, canonicalOrg = null,
        importance = null, summary = null, sourceUrl = null, postedAt = "2026-10-0${id}T00:00:00Z",
    )
}
