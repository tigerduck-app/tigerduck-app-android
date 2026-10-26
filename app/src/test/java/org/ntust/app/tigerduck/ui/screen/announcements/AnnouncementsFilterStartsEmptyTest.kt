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
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.network.model.OrgLabel
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import org.ntust.app.tigerduck.ui.screen.announcements.AnnouncementsViewModel.LoadState
import org.ntust.app.tigerduck.util.AppReturns
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The department chips start with nothing selected each time the page opens: a new back-stack
 * entry has nothing saved. An install from before the "Remember bulletin filters" option went
 * still has what that option stored: it selects no chip, and it is dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnnouncementsFilterStartsEmptyTest {

    @get:Rule val main = MainDispatcherRule()

    private val context = RuntimeEnvironment.getApplication()

    /** What an install with the option on stored: the option itself, and the departments it kept. */
    private val stored = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).apply {
        edit()
            .putBoolean("rememberAnnouncementFilter", true)
            .putString("savedAnnouncementOrgs", """["library"]""")
            .commit()
    }
    private val prefs = AppPreferences(context).apply {
        announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3"
    }
    private val cache = BulletinCache(context)
    private val repository = BulletinRepository()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = when {
                path.endsWith("/bulletins") -> LIST
                path.endsWith("/bulletins/taxonomy") -> TAXONOMY
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

    private fun viewModel(): AnnouncementsViewModel {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        val api = BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
        return AnnouncementsViewModel(
            SavedStateHandle(), api, cache, repository, BulletinReadStateStore(context),
            testDemoAccount(context, prefs, cache), testCredentials(context), AppReturns(now = { 0L }, register = {}),
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
    fun `a first visit opens with no department selected`() {
        val vm = viewModel()
        assertEquals(emptySet<String>(), vm.state.value.selectedOrgs)
        vm.load()
        eventually("the list loads") { vm.state.value.loadState == LoadState.Loaded }
        eventually("the department list arrives") { vm.state.value.taxonomy != null }

        assertEquals(emptySet<String>(), vm.state.value.selectedOrgs)
        assertEquals(setOf(1, 2), vm.shownIds())
    }

    @Test
    fun `a return visit with the department list in memory opens with no department selected`() {
        runBlocking { repository.getOrFetchTaxonomy { TAXONOMY_VALUE } }
        runBlocking { cache.save(listOf(bulletin(1, "library"), bulletin(2, "registrar"))) }

        val vm = viewModel()

        assertEquals(emptySet<String>(), vm.state.value.selectedOrgs)
        vm.load()
        eventually("the cached list shows") { vm.state.value.items.isNotEmpty() }
        assertEquals(setOf(1, 2), vm.shownIds())
    }

    @Test
    fun `what the option stored is dropped`() {
        assertFalse(stored.contains("rememberAnnouncementFilter"))
        assertFalse(stored.contains("savedAnnouncementOrgs"))
    }

    private fun bulletin(id: Int, org: String) = BulletinSummary(
        id = id, externalId = "e$id", title = "Bulletin $id", titleClean = null, canonicalOrg = org,
        importance = null, summary = null, sourceUrl = null, postedAt = "2026-10-0${id}T00:00:00Z",
    )

    private companion object {
        const val LIST = """{"items":[""" +
            """{"id":1,"external_id":"e1","title":"One","canonical_org":"library","content_tags":[],"posted_at":"2026-10-01T00:00:00Z"},""" +
            """{"id":2,"external_id":"e2","title":"Two","canonical_org":"registrar","content_tags":[],"posted_at":"2026-10-02T00:00:00Z"}""" +
            """],"next_cursor":null}"""
        const val TAXONOMY = """{"orgs":[{"id":"library","label":"圖書館"},{"id":"registrar","label":"註冊組"}],""" +
            """"tags":[],"default_tags":[]}"""
        val TAXONOMY_VALUE = TaxonomyResponse(
            orgs = listOf(OrgLabel("library", "圖書館"), OrgLabel("registrar", "註冊組")),
            tags = emptyList(),
            defaultTags = emptyList(),
        )
    }
}
