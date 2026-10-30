package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
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
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.ui.navigation.popIfTop
import org.ntust.app.tigerduck.util.AppReturns
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The backend keeps bulletin subscriptions only for a signed-in account (backend #89), so their
 * settings are offered, and stay open, only while one is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BulletinSettingsSignedInTest {

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
        val vm = AnnouncementsViewModel(
            SavedStateHandle(), api(), cache, BulletinRepository(), BulletinReadStateStore(context),
            testDemoAccount(context, prefs, cache, DebugFixtureStore(context)), testCredentials(context),
            AppReturns(now = { 0L }, register = {}),
        )
        composeRule.setContent {
            MaterialTheme {
                AnnouncementsScreen(onOpenBulletin = {}, onOpenSubscriptions = {}, signedIn = signedIn, viewModel = vm)
            }
        }
        composeRule.waitForIdle()
    }

    private fun settingsButtons() = composeRule
        .onAllNodesWithContentDescription(context.getString(R.string.bulletin_notifications_title))
        .fetchSemanticsNodes().size

    @Test
    fun `signed out, the bulletins page offers no push settings`() {
        announcements(signedIn = false)

        assertEquals(0, settingsButtons())
    }

    @Test
    fun `signed in, the bulletins page offers the push settings`() {
        announcements(signedIn = true)

        assertEquals(1, settingsButtons())
    }

    private fun settingsPage(signedIn: Boolean): Int {
        var closed = 0
        val vm = SubscriptionSettingsViewModel(api(), BulletinRepository(), SystemPermissions(context))
        composeRule.setContent {
            MaterialTheme { SubscriptionSettingsScreen(signedIn = signedIn, onBack = { closed++ }, viewModel = vm) }
        }
        composeRule.waitForIdle()
        return closed
    }

    @Test
    fun `the push settings close once the account signs out`() {
        assertEquals(1, settingsPage(signedIn = false))
    }

    @Test
    fun `the push settings stay open while signed in`() {
        assertEquals(0, settingsPage(signedIn = true))
    }

    /**
     * Signed out while another page was over the settings, as on another tab: nothing closes
     * under that page, and the settings close once they are back on top. As the app wires it,
     * the settings pop only themselves ([popIfTop]).
     */
    @Test
    fun `signed out under another page, the push settings close once back on top`() {
        var signedIn by mutableStateOf(true)
        lateinit var nav: NavHostController
        val vm = SubscriptionSettingsViewModel(api(), BulletinRepository(), SystemPermissions(context))
        composeRule.setContent {
            nav = rememberNavController()
            MaterialTheme {
                NavHost(nav, startDestination = "bulletins") {
                    composable("bulletins") { Text("bulletins") }
                    composable("settings") { entry ->
                        SubscriptionSettingsScreen(signedIn = signedIn, onBack = { nav.popIfTop(entry) }, viewModel = vm)
                    }
                    composable("other") { Text("other") }
                }
            }
        }
        composeRule.runOnIdle { nav.navigate("settings") }
        composeRule.runOnIdle { nav.navigate("other") }

        composeRule.runOnIdle { signedIn = false }
        composeRule.waitForIdle()
        assertEquals("other", nav.currentDestination?.route)

        composeRule.runOnIdle { nav.popBackStack() }
        composeRule.waitForIdle()
        assertEquals("bulletins", nav.currentDestination?.route)
    }
}
