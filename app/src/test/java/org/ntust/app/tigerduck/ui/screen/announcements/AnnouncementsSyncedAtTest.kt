@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.ui.screen.announcements.AnnouncementsViewModel.LoadState
import org.ntust.app.tigerduck.util.AppReturns
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The bulletin page's "Last synced" row: when a refresh last brought the list from the server,
 * kept with the cached list and cleared with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnnouncementsSyncedAtTest {

    @get:Rule val main = MainDispatcherRule()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
    private val cache = BulletinCache(context)
    private val credentials = testCredentials(context)

    /** Whether the backend answers the list; the taxonomy it never answers, as the page allows. */
    @Volatile private var listAnswers = true

    /** Whether the list has a page behind its first, so the walk through older pages runs. */
    @Volatile private var paged = false

    /** A list answer waits on the gate under its cursor ([FIRST] for the newest page) before it goes out. */
    private val listGates = ConcurrentHashMap<String, CountDownLatch>()
    private val listRequests = AtomicInteger()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val ok = listAnswers && request.url.encodedPath.endsWith("/bulletins")
            val cursor = request.url.queryParameter("cursor") ?: FIRST
            if (ok) {
                listRequests.incrementAndGet()
                listGates[cursor]?.await(5, TimeUnit.SECONDS)
            }
            val body = when {
                !ok -> "{}"
                !paged -> LIST
                cursor == FIRST -> PAGE1
                else -> PAGE2
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (ok) 200 else 503)
                .message(if (ok) "OK" else "Unavailable")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun viewModel(): AnnouncementsViewModel {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        val api = BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
        return AnnouncementsViewModel(
            SavedStateHandle(), api, cache, BulletinRepository(), BulletinReadStateStore(context),
            testDemoAccount(context, prefs, cache), credentials, AppReturns(now = { 0L }, register = {}),
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

    /**
     * What [org.ntust.app.tigerduck.auth.AuthService.logout] does to the bulletins: the school
     * account's generation moves, and the cached list goes with its date.
     */
    private fun signOut() {
        credentials.signOutSchoolAccount()
        runBlocking { cache.clear() }
    }

    @Test
    fun `a refresh that lands dates the list, and the date outlives the page`() {
        val before = System.currentTimeMillis()
        val vm = viewModel()
        vm.load()
        eventually("the first refresh is saved") { runBlocking { cache.loadFetchedAt() } != 0L }
        val fetched = runBlocking { cache.loadFetchedAt() }
        assertTrue("$fetched is not this refresh's time", fetched in before..System.currentTimeMillis())
        assertEquals(fetched, vm.state.value.syncedAtMs)

        // The next visit opens on the cached list and its date; a refresh that fails then
        // leaves both.
        listAnswers = false
        val next = viewModel()
        next.load()
        eventually("the next visit's refresh fails") { next.state.value.loadState is LoadState.Failed }
        assertEquals(fetched, next.state.value.syncedAtMs)
        assertEquals(fetched, runBlocking { cache.loadFetchedAt() })
    }

    @Test
    fun `a failed refresh leaves the date where it was`() {
        val vm = viewModel()
        vm.load()
        eventually("the first refresh is saved") { runBlocking { cache.loadFetchedAt() } != 0L }
        val fetched = vm.state.value.syncedAtMs
        assertTrue(fetched > 0L)

        listAnswers = false
        vm.refresh()
        eventually("the refresh fails") { vm.state.value.loadState is LoadState.Failed }

        assertEquals(fetched, vm.state.value.syncedAtMs)
        assertEquals(fetched, runBlocking { cache.loadFetchedAt() })
    }

    /** What the school sign-out does to the bulletins: the list goes, and its date with it. */
    @Test
    fun `the date goes with the cached list, and only with it`() = runBlocking {
        cache.saveFetchedAt(5_000L)
        cache.save(emptyList())
        assertEquals(5_000L, cache.loadFetchedAt())

        cache.clear()

        assertEquals(0L, cache.loadFetchedAt())
    }

    /**
     * A refresh still out at the school sign-out: the sign-out clears the cached list and its
     * date, and the page that then arrives must not write them back -- the departing student's
     * list would be the next one's first paint. The list starts over instead, and the next visit
     * reads the feed again; as on the iPhone.
     */
    @Test
    fun `a refresh that returns after the sign-out writes neither the list nor the date`() {
        val gate = CountDownLatch(1).also { listGates[FIRST] = it }
        val vm = viewModel()
        vm.load()
        eventually("the list is asked for") { listRequests.get() == 1 }

        signOut()
        gate.countDown()

        eventually("the page is dropped") { vm.state.value.loadState == LoadState.Idle }
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(0L, vm.state.value.syncedAtMs)
        assertTrue(runBlocking { cache.load() }.isEmpty())
        assertEquals(0L, runBlocking { cache.loadFetchedAt() })

        // The next visit starts over, from the feed.
        listGates.clear()
        vm.load()
        eventually("the next visit's refresh lands") { vm.state.value.loadState == LoadState.Loaded }
        assertEquals(listOf(1), vm.state.value.items.map { it.id })
        assertTrue(vm.state.value.syncedAtMs > 0L)
        assertEquals(listOf(1), runBlocking { cache.load() }.map { it.id })
    }

    /**
     * A refresh still out when the API endpoint moves to another backend: the move lets go of the
     * cached list, signed in or not, and the old backend's page that then arrives must not write
     * it back, nor merge by id into the new backend's rows (#592).
     */
    @Test
    fun `a refresh that returns after the endpoint moved writes neither the list nor the date`() {
        val gate = CountDownLatch(1).also { listGates[FIRST] = it }
        val vm = viewModel()
        vm.load()
        eventually("the list is asked for") { listRequests.get() == 1 }

        prefs.announcementApiBaseUrlOverride = "http://127.0.0.1:10/v3"
        runBlocking { cache.clear() }
        gate.countDown()

        eventually("the page is dropped") { vm.state.value.loadState == LoadState.Idle }
        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(runBlocking { cache.load() }.isEmpty())
        assertEquals(0L, runBlocking { cache.loadFetchedAt() })

        // The next visit lists the new backend's.
        listGates.clear()
        vm.load()
        eventually("the next visit's refresh lands") { vm.state.value.loadState == LoadState.Loaded }
        assertEquals(listOf(1), runBlocking { cache.load() }.map { it.id })
    }

    /**
     * The page kept in memory, as when its tab is the first and so never leaves the back stack:
     * a return after an endpoint move lists the new backend's, not the old one's still in memory.
     */
    @Test
    fun `a return after the endpoint moved fetches the new backend's list`() {
        val vm = viewModel()
        vm.load()
        eventually("the first refresh lands") { vm.state.value.loadState == LoadState.Loaded }
        assertEquals(1, listRequests.get())

        prefs.announcementApiBaseUrlOverride = "http://127.0.0.1:10/v3"
        runBlocking { cache.clear() }
        vm.load()

        eventually("the new backend is asked") { listRequests.get() == 2 }
        eventually("its list lands") { vm.state.value.loadState == LoadState.Loaded }
    }

    /** The walk through older pages behind a refresh writes the list too, and is dropped the same way. */
    @Test
    fun `an older page that returns after the sign-out writes nothing either`() {
        paged = true
        val gate = CountDownLatch(1).also { listGates["2"] = it }
        val vm = viewModel()
        vm.load()
        eventually("the first page lands") { vm.state.value.loadState == LoadState.Loaded }
        assertEquals(listOf(2), vm.state.value.items.map { it.id })
        // The walk waits a moment after the first page, on the test clock -- and queues that
        // wait only once the page is saved, on an IO thread, after loadState already says
        // Loaded. So the clock is moved with each look rather than once up front: moved before
        // the wait is queued, it would move nothing, and the walk would never start.
        eventually("the older page is asked for") {
            main.dispatcher.scheduler.advanceUntilIdle()
            listRequests.get() == 2
        }

        signOut()
        gate.countDown()

        eventually("the page is dropped") { vm.state.value.loadState == LoadState.Idle }
        assertTrue(vm.state.value.items.isEmpty())
        assertEquals(0L, vm.state.value.syncedAtMs)
        assertTrue(runBlocking { cache.load() }.isEmpty())
        assertEquals(0L, runBlocking { cache.loadFetchedAt() })
    }

    /**
     * The check and the write share the cache's lock, which the sign-out's clear takes too: a
     * clear that comes while a save is asking whether to write waits for the write, then removes
     * it. Without that, a save let through just before the sign-out could land after the clear.
     */
    @Test
    fun `the sign-out's clear waits for a save that is asking whether to write`() {
        val asking = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val saving = thread {
            runBlocking {
                cache.saveIf(listOf(bulletin(1)), 5_000L) {
                    asking.countDown()
                    answer.await(5, TimeUnit.SECONDS)
                    true
                }
            }
        }
        assertTrue(asking.await(5, TimeUnit.SECONDS))
        val clearing = thread { runBlocking { cache.clear() } }
        // Time for a clear that does not wait to run; one that waits for the lock cannot.
        Thread.sleep(200)
        answer.countDown()
        saving.join()
        clearing.join()

        assertTrue(runBlocking { cache.load() }.isEmpty())
        assertEquals(0L, runBlocking { cache.loadFetchedAt() })
    }

    @Test
    fun `a save refused by its check writes neither the list nor the date`() = runBlocking {
        assertFalse(cache.saveIf(listOf(bulletin(1)), 5_000L) { false })
        assertTrue(cache.load().isEmpty())
        assertEquals(0L, cache.loadFetchedAt())

        assertTrue(cache.saveIf(listOf(bulletin(1)), 5_000L) { true })
        assertEquals(listOf(1), cache.load().map { it.id })
        assertEquals(5_000L, cache.loadFetchedAt())
    }

    private fun bulletin(id: Int) = BulletinSummary(
        id = id, externalId = "e$id", title = "Bulletin $id", titleClean = null, canonicalOrg = null,
        importance = null, summary = null, sourceUrl = null, postedAt = "2026-10-0${id}T00:00:00Z",
    )

    private companion object {
        const val FIRST = "first"
        const val LIST = """{"items":[{"id":1,"external_id":"e1","title":"Bulletin","posted_at":"2026-10-01T00:00:00Z"}],"next_cursor":null}"""
        const val PAGE1 = """{"items":[{"id":2,"external_id":"e2","title":"Newer","posted_at":"2026-10-02T00:00:00Z"}],"next_cursor":2}"""
        const val PAGE2 = """{"items":[{"id":1,"external_id":"e1","title":"Bulletin","posted_at":"2026-10-01T00:00:00Z"}],"next_cursor":null}"""
    }
}
