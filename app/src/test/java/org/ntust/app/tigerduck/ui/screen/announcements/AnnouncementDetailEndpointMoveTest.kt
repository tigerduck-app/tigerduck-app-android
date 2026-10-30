package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bulletin's page whose fetch is still out when the API endpoint moves (#592): the id names
 * another backend's bulletin from then on, so the old one's detail is kept nowhere and not shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnnouncementDetailEndpointMoveTest {

    @get:Rule val main = MainDispatcherRule()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
    private val cache = BulletinCache(context)
    private val repository = BulletinRepository()

    @Volatile private var gate = CountDownLatch(1)
    private val detailRequests = AtomicInteger()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val detail = request.url.encodedPath.endsWith("/bulletins/1")
            if (detail) {
                detailRequests.incrementAndGet()
                gate.await(5, TimeUnit.SECONDS)
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (detail) 200 else 503)
                .message(if (detail) "OK" else "Unavailable")
                .body((if (detail) DETAIL else "{}").toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "never: $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a detail that lands after the endpoint moved is kept nowhere and not shown`() {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        val api = BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
        val vm = AnnouncementDetailViewModel(
            SavedStateHandle(mapOf("id" to 1)), api, cache, repository, BulletinReadStateStore(context),
        )
        eventually("the detail is asked for") { detailRequests.get() == 1 }

        prefs.announcementApiBaseUrlOverride = "http://127.0.0.1:10/v3"
        repository.forget()
        runBlocking { cache.clear() }
        gate.countDown()
        // Time for the reply to land and for whatever it would write.
        Thread.sleep(300)
        main.dispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.detail(1))
        assertNull(runBlocking { cache.loadDetail(1) })
        assertFalse(vm.state.value is AnnouncementDetailViewModel.State.Loaded)
    }

    /** The move comes while the detail's save waits: the page shows nothing of the old backend. */
    @Test
    fun `a detail whose save the endpoint move overtakes is not shown`() {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        val api = BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
        // A clear started and held, as an earlier move's on a busy application scope: the save waits for it.
        val clearHeld = CountDownLatch(1)
        val clearThread = Executors.newSingleThreadExecutor().apply { execute { clearHeld.await(5, TimeUnit.SECONDS) } }
        cache.clearIn(CoroutineScope(clearThread.asCoroutineDispatcher()))
        gate.countDown()
        val vm = AnnouncementDetailViewModel(
            SavedStateHandle(mapOf("id" to 1)), api, cache, repository, BulletinReadStateStore(context),
        )
        eventually("the detail is asked for") { detailRequests.get() == 1 }
        Thread.sleep(300)
        main.dispatcher.scheduler.advanceUntilIdle()

        repository.forget()
        clearHeld.countDown()
        Thread.sleep(300)
        main.dispatcher.scheduler.advanceUntilIdle()

        assertNull(repository.detail(1))
        assertNull(runBlocking { cache.loadDetail(1) })
        assertFalse(vm.state.value is AnnouncementDetailViewModel.State.Loaded)
        clearThread.shutdown()
    }

    private companion object {
        const val DETAIL = """{"id":1,"external_id":"e1","title":"Old","content_tags":[],"summary":null,""" +
            """"body_clean":"old backend","body_md":null}"""
    }
}
