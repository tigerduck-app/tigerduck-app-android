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
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.ntust.app.tigerduck.network.model.OrgLabel
import org.ntust.app.tigerduck.network.model.TagLabel
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import org.ntust.app.tigerduck.ui.screen.announcements.AnnouncementsViewModel.LoadState
import org.ntust.app.tigerduck.util.AppReturns
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A selection belongs to the visit that made it: kept in the back-stack entry's
 * [SavedStateHandle], it comes back when Android kills the process while a bulletin is open and
 * the user presses Back into a recreated view model. It goes on once the chips can show it, and
 * only the ids the department and category lists still have. A new entry, with nothing saved,
 * starts empty ([AnnouncementsFilterStartsEmptyTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnnouncementsSavedSelectionTest {

    @get:Rule val main = MainDispatcherRule()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context).apply {
        announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
    }
    private val cache = BulletinCache(context)
    private val repository = BulletinRepository()

    /** The backend answers the department list only once [taxonomyGate] opens. */
    private val taxonomyGate = CountDownLatch(1)
    private val taxonomyRequests = AtomicInteger()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/bulletins") -> LIST
                path.endsWith("/bulletins/taxonomy") -> {
                    taxonomyRequests.incrementAndGet()
                    taxonomyGate.await(5, TimeUnit.SECONDS)
                    TAXONOMY
                }
                else -> null
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (body != null) 200 else 503)
                .message(if (body != null) "OK" else "Unavailable")
                .body((body ?: "{}").toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    @After fun openGate() = taxonomyGate.countDown()

    /** What the entry saved: a department and a category, and one id of each the lists no longer have. */
    private fun savedSelection() = SavedStateHandle(
        mapOf(
            "selectedOrgs" to arrayOf("library", "gone"),
            "selectedTags" to arrayOf("exam", "gone"),
        )
    )

    private fun viewModel(handle: SavedStateHandle): AnnouncementsViewModel {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        val api = BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
        return AnnouncementsViewModel(
            handle, api, cache, repository, BulletinReadStateStore(context), testDemoAccount(context, prefs, cache), testCredentials(context),
            AppReturns(now = { 0L }, register = {}),
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

    private fun AnnouncementsViewModel.shownIds() = state.value.displayed.map { it.id }.toSet()

    @Test
    fun `the entry's selection returns once the department list arrives, only the ids it has`() {
        val vm = viewModel(savedSelection())
        assertEquals(emptySet<String>(), vm.state.value.selectedOrgs)
        assertEquals(emptySet<String>(), vm.state.value.selectedTags)
        vm.load()
        eventually("the list loads") { vm.state.value.loadState == LoadState.Loaded }
        eventually("the department list is asked for") { taxonomyRequests.get() > 0 }
        // No chips on screen yet, so nothing is filtered by them.
        assertEquals(setOf(1, 2, 3), vm.shownIds())

        taxonomyGate.countDown()
        eventually("the department list arrives") { vm.state.value.taxonomy != null }

        assertEquals(setOf("library"), vm.state.value.selectedOrgs)
        assertEquals(setOf("exam"), vm.state.value.selectedTags)
        assertEquals(setOf(1), vm.shownIds())
    }

    @Test
    fun `with the department list in memory the entry's selection is there from the start`() {
        runBlocking { repository.getOrFetchTaxonomy { TAXONOMY_VALUE } }
        runBlocking {
            cache.save(listOf(bulletin(1, "library", "exam"), bulletin(2, "library", "scholarship"), bulletin(3, "registrar", "exam")))
        }

        val vm = viewModel(savedSelection())

        assertEquals(setOf("library"), vm.state.value.selectedOrgs)
        assertEquals(setOf("exam"), vm.state.value.selectedTags)
        vm.load()
        eventually("the cached list shows") { vm.state.value.items.isNotEmpty() }
        assertEquals(setOf(1), vm.shownIds())
    }

    @Test
    fun `a pick on this visit is saved for the entry`() {
        taxonomyGate.countDown()
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.load()
        eventually("the department list arrives") { vm.state.value.taxonomy != null }

        vm.setOrgFilter(setOf("registrar"))
        vm.setTagFilter(setOf("scholarship"))
        assertEquals(listOf("registrar"), handle.get<Array<String>>("selectedOrgs")?.toList())
        assertEquals(listOf("scholarship"), handle.get<Array<String>>("selectedTags")?.toList())

        vm.setOrgFilter(emptySet())
        assertEquals(emptyList<String>(), handle.get<Array<String>>("selectedOrgs")?.toList())
    }

    private fun bulletin(id: Int, org: String, tag: String) = BulletinSummary(
        id = id, externalId = "e$id", title = "Bulletin $id", titleClean = null, canonicalOrg = org,
        contentTags = listOf(tag), importance = null, summary = null, sourceUrl = null,
        postedAt = "2026-10-0${id}T00:00:00Z",
    )

    private companion object {
        const val LIST = """{"items":[""" +
            """{"id":1,"external_id":"e1","title":"One","canonical_org":"library","content_tags":["exam"],"posted_at":"2026-10-01T00:00:00Z"},""" +
            """{"id":2,"external_id":"e2","title":"Two","canonical_org":"library","content_tags":["scholarship"],"posted_at":"2026-10-02T00:00:00Z"},""" +
            """{"id":3,"external_id":"e3","title":"Three","canonical_org":"registrar","content_tags":["exam"],"posted_at":"2026-10-03T00:00:00Z"}""" +
            """],"next_cursor":null}"""
        const val TAXONOMY = """{"orgs":[{"id":"library","label":"圖書館"},{"id":"registrar","label":"註冊組"}],""" +
            """"tags":[{"id":"exam","label":"考試"},{"id":"scholarship","label":"獎學金"}],"default_tags":[]}"""
        val TAXONOMY_VALUE = TaxonomyResponse(
            orgs = listOf(OrgLabel("library", "圖書館"), OrgLabel("registrar", "註冊組")),
            tags = listOf(TagLabel("exam", "考試"), TagLabel("scholarship", "獎學金")),
            defaultTags = emptyList(),
        )
    }
}
