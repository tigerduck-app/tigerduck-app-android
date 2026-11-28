package org.ntust.app.tigerduck.browser

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WebView profile the links the app was handed load in
 * ([org.ntust.app.tigerduck.ui.screen.browser.InAppBrowser.openLink]): a mail's, a bulletin's or a
 * Moodle page's link to another site, the app's website, as the iPhone opens them in an
 * `SFSafariViewController`, whose data is apart from the app's. Such a link can come from anyone, so
 * its page never runs in the app's own profile, which holds the student's school sessions: there it
 * could send a school site requests with the student's cookies, or force a sign-in.
 *
 * One profile, kept under one name, so a site the student signs in to here stays signed in; never the
 * app's own, and never one opened at risk ([BrowserRiskProfiles]). Nothing of the app's goes in: no
 * automatic sign-in, no fill, no cookie or token, so a school page loads here signed out. Only where
 * the WebView has profiles ([supported]); elsewhere a link opens in the phone's browser. Everything it
 * keeps goes with the browser's data ([AndroidWebViewStorage]), at every school sign-out and sign-in
 * and by Settings' website data.
 */
class BrowserLinksProfile(private val profiles: WebViewProfiles) {
    fun supported(): Boolean = runCatching { profiles.supported() }.getOrDefault(false)

    /**
     * Deletes everything the profile keeps. Not in time, nor settled, when the WebView did not delete
     * it: its cookies may still be there, so a sign-out's clearing stays owed ([BrowserCleanup]).
     * Nothing to delete where the WebView has no profiles, which no link opened in.
     */
    suspend fun clear(timeoutMs: Long): StorageClearing {
        if (!supported()) return StorageClearing(inTime = true)
        val failed = AtomicBoolean(false)
        val deletion = startDeletion { done ->
            try {
                profiles.clear(NAME) { done() }
            } catch (e: Throwable) {
                // An Error too, as from an older WebView: a clearing waits for done.
                Log.w(TAG, "the links profile was not cleared: ${e.javaClass.simpleName}")
                failed.set(true)
                done()
            }
        }
        val inTime = reportsInTime(deletion, timeoutMs) && !failed.get()
        return StorageClearing(inTime) {
            deletion.await()
            !failed.get()
        }
    }

    companion object {
        /** Not the app's own profile's ("Default"), and not under [BrowserRiskProfiles.PREFIX], which a sweep deletes. */
        const val NAME = "tigerduck-links"
        private const val TAG = "BrowserLinksProfile"

        /** The process's own: a WebView's profiles are the process's. */
        val shared = BrowserLinksProfile(AndroidWebViewProfiles)
    }
}
