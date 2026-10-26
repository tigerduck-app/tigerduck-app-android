package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.SavedStateHandle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.core.strings.R
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the bulletin page shows under the title: the body the iPhone would pick, or the summary
 * when there is no body to show, including under the error when the body does not load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AnnouncementDetailBodyTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
    private val repository = BulletinRepository()

    /** The detail endpoint's answer; null answers 503. The taxonomy never answers. */
    @Volatile private var detailJson: String? = null

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val body = detailJson?.takeIf { request.url.encodedPath.endsWith("/bulletins/1") }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (body != null) 200 else 503)
                .message(if (body != null) "OK" else "Unavailable")
                .body((body ?: "{}").toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun open() {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        val api = BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
        val vm = AnnouncementDetailViewModel(
            SavedStateHandle(mapOf("id" to 1)), api, BulletinCache(context), repository, BulletinReadStateStore(context),
        )
        composeRule.setContent {
            MaterialTheme { AnnouncementDetailScreen(browserPreference = "", onBack = {}, viewModel = vm) }
        }
    }

    /** The detail's IO and the markdown parse both run off the main thread. */
    private fun waitForText(text: String) =
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    private fun shows(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun listRow(summary: String?) = BulletinSummary(
        id = 1, externalId = "e1", title = "標題", titleClean = null, canonicalOrg = null,
        importance = null, summary = summary, sourceUrl = null, postedAt = null,
    )

    @Test
    fun `the cleaned body is shown over the scraped one`() {
        detailJson = detail(bodyClean = "清理後內文", bodyMd = "原始內文")
        open()
        waitForText("清理後內文")
        assertFalse(shows("原始內文"))
    }

    @Test
    fun `a bulletin with no body shows its summary`() {
        detailJson = detail(summary = "詳細摘要")
        open()
        waitForText("詳細摘要")
    }

    @Test
    fun `a bulletin with neither body nor summary shows the list row's summary`() {
        repository.putSummaries(listOf(listRow("列表摘要")))
        detailJson = detail()
        open()
        waitForText("列表摘要")
    }

    @Test
    fun `a body that fails to load still shows the summary under the error`() {
        repository.putSummaries(listOf(listRow("列表摘要")))
        detailJson = null
        open()
        waitForText(context.getString(R.string.bulletin_body_load_failed))
        waitForText("列表摘要")
    }

    @Test
    fun `bold flush against punctuation renders without its asterisks`() {
        detailJson = detail(bodyClean = "說明**截止日期：**10/31 前")
        open()
        waitForText("截止日期：")
        assertFalse(shows("**"))
    }

    private fun detail(bodyClean: String? = null, bodyMd: String? = null, summary: String? = null): String {
        fun field(value: String?) = value?.let { "\"$it\"" } ?: "null"
        return """{"id":1,"external_id":"e1","title":"標題","content_tags":[],"summary":${field(summary)},""" +
            """"body_clean":${field(bodyClean)},"body_md":${field(bodyMd)}}"""
    }
}
