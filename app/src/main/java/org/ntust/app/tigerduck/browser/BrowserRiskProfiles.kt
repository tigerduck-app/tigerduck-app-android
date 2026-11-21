package org.ntust.app.tigerduck.browser

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

/** The WebView's named profiles, as [BrowserRiskProfiles] uses them; a test's stand-in. On the main thread. */
interface WebViewProfiles {
    /** Whether this WebView has profiles beside the app's own, and can delete all a profile keeps. */
    fun supported(): Boolean

    /** Every profile's name, on disk or made in this process, the default one's too. */
    fun names(): List<String>

    /** Deletes the profile [name]; throws for one this process loaded, which only a later one can. */
    fun delete(name: String)

    /** Deletes everything the profile [name], one this process made, keeps, calling [done] once it has. */
    fun clear(name: String, done: () -> Unit)
}

/**
 * The WebView profiles a page the student opened at their own risk loads in, as the iPhone opens one
 * in a new `WKWebsiteDataStore.nonPersistent()` (tigerduck-app#958): a new one for each browser
 * opened at risk, under a name never used again, so it starts signed in to nothing and none of the
 * app's own profile's cookies, storage, cache or connections reach it. Only where the WebView has
 * profiles ([supported]); elsewhere no page opens at risk.
 *
 * A WebView profile lives on disk, and the process that loaded one cannot delete it. So what a
 * profile keeps is deleted as its browser closes and at every clearing of the browser
 * ([AndroidWebViewStorage]), and the profile itself in a later process, before anything loads one:
 * at the start ([sweepAtStart]), as a browser opens, and at a clearing ([sweep]). Each name is kept
 * ([BrowserCleanup.riskProfiles]) before its profile can be made, so it goes whether or not the
 * WebView lists it, and a start with none never loads the WebView for it.
 */
class BrowserRiskProfiles(private val profiles: WebViewProfiles) {
    /** The ones made in this process, which it cannot delete. */
    private val made = mutableSetOf<String>()

    fun supported(): Boolean = runCatching { profiles.supported() }.getOrDefault(false)

    /**
     * A new profile's name, for one browser opened at risk to load in; null where the WebView has no
     * profiles. Kept, before it can be made, as one a later process deletes.
     */
    @Synchronized
    fun create(cleanup: BrowserCleanup): String? {
        if (!supported()) return null
        val name = PREFIX + UUID.randomUUID()
        cleanup.addRiskProfile(name)
        made += name
        return name
    }

    /** Whether [name] was made in this process: a browser an earlier one opened at risk opens nothing in it. */
    @Synchronized
    fun isMadeHere(name: String): Boolean = name in made

    @Synchronized
    fun madeHere(): Set<String> = made.toSet()

    /** Deletes what the profile [name], made here, keeps, calling [done] once it has, or at once for another. */
    fun clear(name: String, done: () -> Unit = {}) {
        if (!isMadeHere(name)) return done()
        try {
            profiles.clear(name, done)
        } catch (e: Exception) {
            Log.w(TAG, "a profile opened at risk was not cleared: ${e.javaClass.simpleName}")
            done()
        }
    }

    /**
     * Deletes each profile an earlier process made: those kept by name, and any other of theirs the
     * WebView lists. One that would not go stays kept for the next try, as do those made here.
     */
    @Synchronized
    fun sweep(cleanup: BrowserCleanup) {
        val kept = cleanup.riskProfiles()
        if (kept.isEmpty() || !supported()) return
        val listed = runCatching { profiles.names() }.getOrNull().orEmpty().filter { it.startsWith(PREFIX) }
        val deleted = (kept + listed).filter { name ->
            name !in made && runCatching { profiles.delete(name) }
                .onFailure { Log.w(TAG, "a profile opened at risk was not deleted: ${it.javaClass.simpleName}") }
                .isSuccess
        }
        cleanup.riskProfilesDeleted(deleted.toSet())
    }

    /** At the app's start: [sweep] once the main thread is idle, where a profile may be left. */
    fun sweepAtStart(context: Context) {
        val cleanup = BrowserCleanup.of(context)
        if (cleanup.riskProfiles().isEmpty()) return
        Looper.myQueue().addIdleHandler {
            sweep(cleanup)
            false
        }
    }

    companion object {
        const val PREFIX = "tigerduck-at-risk-"
        private const val TAG = "BrowserRiskProfiles"

        /** The process's own: a WebView's profiles are the process's. */
        val shared = BrowserRiskProfiles(AndroidWebViewProfiles)
    }
}

/** The WebView's own profiles, through androidx.webkit. */
private object AndroidWebViewProfiles : WebViewProfiles {
    override fun supported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

    override fun names(): List<String> = ProfileStore.getInstance().allProfileNames

    override fun delete(name: String) {
        ProfileStore.getInstance().deleteProfile(name)
    }

    override fun clear(name: String, done: () -> Unit) {
        // Asked only for one made here, which is loaded already: never one a later process must delete.
        val profile = ProfileStore.getInstance().getProfile(name) ?: return done()
        WebStorageCompat.deleteBrowsingData(profile.webStorage) { done() }
    }
}
