@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.announcements

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.ntust.app.tigerduck.util.JvmContext
import java.util.concurrent.Executors

/**
 * A subscription settings page as the app builds one, signed in as the first student (`access-a`),
 * its requests answered by [backend] and nothing else. Its view model runs on one thread of its
 * own, as on the phone's main thread, and the backend's answers come back on OkHttp's, so a test
 * waits for what it checks ([eventually]) and acts on the page as the screen does ([onMain]).
 */
class SubscriptionPage : TestWatcher() {
    val backend = SubscriptionBackend()
    private val context = JvmContext()
    private val prefs = AppPreferences(context)
    private val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-subscriptions", Context.MODE_PRIVATE))
    val manager = AuthTokenManager(tokens, backend.client, prefs, "device-1")
    val api = BulletinApiClient(backend.client, prefs, manager)
    private lateinit var main: ExecutorCoroutineDispatcher

    override fun starting(description: Description) {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
        main.close()
    }

    /**
     * Opens the page on the backend at [base], the API endpoint setting, and waits for the rules
     * to show. The backend holds the first student's rules named [rules] before it opens.
     */
    fun open(base: String, vararg rules: String): SubscriptionSettingsViewModel {
        prefs.announcementApiBaseUrlOverride = base
        // Never the real backend, even past this one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        tokens.save(FIRST, "refresh-a", Long.MAX_VALUE)
        backend.seed(FIRST, *rules)
        val page = onMain {
            SubscriptionSettingsViewModel(api, BulletinRepository(), SystemPermissions(context), manager.session)
        }
        eventually("the rules show") { page.state.value.loadState == SubscriptionSettingsViewModel.LoadState.Loaded }
        return page
    }

    /** Runs [block] on the page's thread, as a tap does, and returns once it has. */
    fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    /** Waits until [condition] holds, and fails naming [what] if it never does. */
    fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!onMain(condition)) {
            check(System.currentTimeMillis() < deadline) { "never: $what" }
            Thread.sleep(5)
        }
    }

    /**
     * Waits until the page's thread has run everything queued on it now, and what that queued in
     * turn, a few rounds deep: what a test checks did not happen has had its chance to.
     */
    fun settle() = repeat(5) { onMain { } }

    /** The first student signs out, and the second ([SECOND]) signs in with a session of their own. */
    fun nextStudentSignsIn(vararg rules: String) {
        backend.seed(SECOND, *rules)
        manager.logout()
        manager.session.open()
        tokens.save(SECOND, "refresh-b", Long.MAX_VALUE)
    }

    companion object {
        const val FIRST = "access-a"
        const val SECOND = "access-b"
        const val V3 = "http://127.0.0.1:9/v3"
        const val V4 = "http://127.0.0.1:9/v4"
    }
}
