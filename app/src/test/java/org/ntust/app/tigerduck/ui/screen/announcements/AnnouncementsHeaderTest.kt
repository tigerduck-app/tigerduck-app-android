package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderAtTop
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertHeaderScrollsAway
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertRowEndsWith
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.assertSitsLeftOf
import org.ntust.app.tigerduck.ui.screen.TabHeaderChecks.title
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import org.ntust.app.tigerduck.util.AppReturns
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The header in a chrome overlay, above the list rather than in it: the title, the dot, the
 * filter and the actions, hiding as the list scrolls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-w400dp-h600dp")
class AnnouncementsHeaderTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
    private val cache = BulletinCache(context)

    private val client = OkHttpClient.Builder()
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

    private fun api(): BulletinApiClient {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        return BulletinApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))
    }

    private fun announcements(signedIn: Boolean) {
        runBlocking { cache.save((1..30).map { bulletin(it) }) }
        val vm = AnnouncementsViewModel(
            SavedStateHandle(), api(), cache, BulletinRepository(), BulletinReadStateStore(context),
            testSessions(context),
            AppReturns(now = { 0L }, register = {}),
        )
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false) {
                AnnouncementsScreen(onOpenBulletin = {}, onOpenSubscriptions = {}, signedIn = signedIn, viewModel = vm)
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Bulletin", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun bulletin(id: Int) = BulletinSummary(
        id = id, externalId = "bulletin-$id", title = "Bulletin $id", titleClean = null, canonicalOrg = null,
        importance = null, summary = null, sourceUrl = null, postedAt = "2026-10-01T00:00:00Z",
    )

    private val density get() = composeRule.activity.resources.displayMetrics.density
    private fun text(id: Int) = composeRule.activity.getString(id)

    @Before
    fun signIn() = ServerStatusTracker.setSignedIn(true)

    @After
    fun signOut() = ServerStatusTracker.setSignedIn(false)

    @Test
    fun `the title, dot, unread filter and settings share the row, in that order`() {
        announcements(signedIn = true)
        val title = text(R.string.feature_announcements)
        composeRule.title(title).assertIsDisplayed()
        composeRule.assertHeaderAtTop(title, density)
        val dot = composeRule.onNode(hasContentDescription(text(R.string.bulletin_push_status_unknown)) or
            hasContentDescription(text(R.string.sync_status_failed)) or hasContentDescription(text(R.string.sync_status_syncing)) or
            hasContentDescription(text(R.string.sync_status_ok))).assertIsDisplayed()
        val filter = composeRule.onNodeWithContentDescription(text(R.string.bulletin_show_unread_only_action)).assertIsDisplayed()
        val settings = composeRule.onNodeWithContentDescription(text(R.string.bulletin_notifications_title)).assertIsDisplayed()
        composeRule.assertSitsLeftOf(composeRule.title(title), dot)
        composeRule.assertSitsLeftOf(dot, filter)
        composeRule.assertSitsLeftOf(filter, settings)
        composeRule.assertRowEndsWith(settings, density, targetMargin = 4f)
    }

    @Test
    fun `signed out, the row ends on the filter`() {
        announcements(signedIn = false)
        val filter = composeRule.onNodeWithContentDescription(text(R.string.bulletin_show_unread_only_action))
        // A 24dp glyph in the middle of its 48dp target.
        composeRule.assertRowEndsWith(filter, density, targetMargin = 12f)
    }

    @Test
    fun `the header hides as the list scrolls`() {
        announcements(signedIn = true)
        composeRule.assertHeaderScrollsAway(text(R.string.feature_announcements), density)
    }
}
