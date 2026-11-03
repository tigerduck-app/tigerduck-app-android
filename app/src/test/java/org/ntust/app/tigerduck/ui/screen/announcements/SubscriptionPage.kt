@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.announcements

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    /**
     * The app's `/v4` saves, one for every page this test opens, as the process has one. Its
     * worker runs on the pages' thread here, so what it has done is settled once that thread has
     * run what is queued on it.
     */
    lateinit var saves: SubscriptionListSaves
        private set
    private lateinit var savesScope: CoroutineScope
    private val stores = HashMap<SubscriptionSettingsViewModel, ViewModelStore>()

    override fun starting(description: Description) {
        main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        savesScope = CoroutineScope(SupervisorJob() + main)
        saves = SubscriptionListSaves(api, savesScope)
    }

    /**
     * Nothing the test left running may land after it, once Main is gone: a save's answer moves
     * the ledger, whose collectors, the pages', resume on Main, and a failure there would be
     * reported to whichever test runs next. So the saves first handle every save they began
     * (a test that failed with a request still held gives up after the wait), then the pages are
     * cleared and the saves stopped, on Main.
     */
    override fun finished(description: Description) {
        runCatching {
            eventually("the saves handle every save they began") {
                saves.probe.answersHandled.get() == saves.probe.savesBegun.get()
            }
        }
        onMain {
            stores.values.forEach(ViewModelStore::clear)
            stores.clear()
            savesScope.cancel()
        }
        Dispatchers.resetMain()
        main.close()
    }

    /**
     * Opens the page on the backend at [base], the API endpoint setting, and waits for the rules
     * to show unless told not to ([shown]). The backend holds the first student's rules named
     * [rules] before it opens.
     */
    fun open(base: String, vararg rules: String, shown: Boolean = true): SubscriptionSettingsViewModel {
        prefs.announcementApiBaseUrlOverride = base
        // Never the real backend, even past this one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        tokens.save(FIRST, "refresh-a", Long.MAX_VALUE)
        backend.seed(FIRST, *rules)
        return reopen(shown)
    }

    /**
     * Opens the page once more, on the backend and under the sign-in already set, as the app
     * builds one: a view model of its own back stack entry ([close] clears it). Waits for the
     * rules to show unless told not to ([shown]).
     */
    fun reopen(shown: Boolean = true): SubscriptionSettingsViewModel {
        val page = onMain {
            val store = ViewModelStore()
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SubscriptionSettingsViewModel(api, BulletinRepository(), SystemPermissions(context), manager.session, saves) as T
            }
            ViewModelProvider(store, factory)[SubscriptionSettingsViewModel::class.java].also { stores[it] = store }
        }
        // The page's one read nothing else waits for, which must not be out when the test ends.
        eventually("the taxonomy is in") { page.state.value.taxonomy != null }
        if (shown) eventually("the rules show") { page.state.value.loadState == SubscriptionSettingsViewModel.LoadState.Loaded }
        return page
    }

    /** The student leaves [page]: its back stack entry goes, which clears its view model and cancels its scope. */
    fun close(page: SubscriptionSettingsViewModel) = onMain { stores.remove(page)?.clear() }

    /** Runs [block] on the page's thread, as a tap does, and returns once it has. */
    fun <T> onMain(block: () -> T): T = runBlocking(main) { block() }

    /** Waits until [condition] holds, and fails naming [what] if it never does. */
    fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!onMain(condition)) {
            if (System.currentTimeMillis() > deadline) fail("never: $what")
            Thread.sleep(5)
        }
    }

    /**
     * Waits until [page] has handled [count] answers to its reads since it opened,
     * the first read's among them, and fails if it handled more: an answer is read on OkHttp's
     * thread and handled on the page's a moment later, which only the page itself can tell.
     */
    fun awaitAnswers(page: SubscriptionSettingsViewModel, count: Int) {
        eventually("the page handles $count answers") { page.probe.answersHandled.get() >= count }
        assertEquals("answers the page handled", count, onMain { page.probe.answersHandled.get() })
    }

    /**
     * Waits until the app's saves have handled [count] answers or refusals, and fails if
     * they handled more; then until the pages have shown what those left.
     */
    fun awaitSaves(count: Int) {
        eventually("the saves handle $count answers") { saves.probe.answersHandled.get() >= count }
        assertEquals("answers the saves handled", count, onMain { saves.probe.answersHandled.get() })
        onMain { }
    }

    /** How many saves the app has begun, once the pages' thread has run what is queued there. */
    fun savesBegun(): Int = onMain { saves.probe.savesBegun.get() }

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
        const val V4 = "http://127.0.0.1:9/v4"
    }
}
