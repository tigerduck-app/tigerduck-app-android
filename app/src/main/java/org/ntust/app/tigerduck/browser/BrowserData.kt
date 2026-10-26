package org.ntust.app.tigerduck.browser

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Moves on whenever the browser's data is cleared, so a browser screen open
 * at that moment, which shows the departing student's pages, closes; and
 * says while a clearing runs, so a screen opened meanwhile waits for it.
 */
object BrowserSession {
    private val state = MutableStateFlow(0)
    val generation: StateFlow<Int> = state.asStateFlow()

    private val clearingState = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = clearingState.asStateFlow()

    private val pendingDeletions = MutableStateFlow(0)

    /**
     * False while a deletion the WebView has not confirmed is still under
     * way, however long after its clearing gave up waiting: the browser
     * does not open until it has.
     */
    val usable: Boolean get() = pendingDeletions.value == 0

    /** Held while the session moves on or a clearing starts, and while [ifStill] writes. */
    private val lock = Any()

    fun end() = synchronized(lock) { state.update { it + 1 } }

    internal fun setClearing(clearing: Boolean) = synchronized(lock) {
        clearingState.value = clearing
    }

    /**
     * Runs [write] if the session is still [session] and nothing is clearing: what a download
     * keeps in the browser's cookies, which must not outlive a clearing that left the download
     * running. One lock with [end] and [setClearing], so a clearing cannot start between the check
     * and the write; a write before it starts is cleared with the rest.
     */
    fun ifStill(session: Int, write: () -> Unit) = synchronized(lock) {
        if (state.value == session && !clearingState.value) write()
    }

    internal fun deletionStarted() = pendingDeletions.update { it + 1 }

    internal fun deletionDone() = pendingDeletions.update { (it - 1).coerceAtLeast(0) }
}

/**
 * Starts [delete], which calls back once its deletion is done. Until it has,
 * however late, the browser does not open ([BrowserSession.usable]).
 */
fun startDeletion(delete: (onDone: () -> Unit) -> Unit): Deferred<Unit> {
    val done = CompletableDeferred<Unit>()
    BrowserSession.deletionStarted()
    done.invokeOnCompletion { BrowserSession.deletionDone() }
    delete { done.complete(Unit) }
    return done
}

/** Waits up to [timeoutMs] for [deletion]: false when it has not reported by then, and it goes on. */
suspend fun reportsInTime(deletion: Deferred<Unit>, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) { deletion.await() } != null

/** The WebView's own stores: cookies, web storage, saved HTTP auth and the HTTP cache. */
fun interface WebViewStorage {
    suspend fun clear(): StorageClearing
}

/**
 * What [WebViewStorage.clear] did: whether every deletion reported done in
 * time, and [settled], which waits however long for those still under way and
 * says whether everything was deleted in the end.
 */
class StorageClearing(val inTime: Boolean, val settled: suspend () -> Boolean = { inTime })

/**
 * Whether the browser still has to be cleared, kept on disk so a clearing a
 * process death cut short is not forgotten: set before an account change is
 * published (a sign-out, a sign-in taken), and taken off only by a clearing
 * every deletion of which reported done. While it is set the browser does not
 * open, and the app clears it again as it starts.
 */
class BrowserCleanup(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isPending(): Boolean = prefs.getBoolean(KEY_PENDING, false)

    /**
     * Written to disk before it returns: the account change it precedes may be
     * the last thing the process does. Returns this request, for [markDone].
     */
    fun markPending(): Long = synchronized(LOCK) {
        val request = prefs.getLong(KEY_REQUEST, 0) + 1
        prefs.edit().putBoolean(KEY_PENDING, true).putLong(KEY_REQUEST, request).commit()
        request
    }

    /** Takes the mark off, unless an account change since [request] asked for another clearing. */
    fun markDone(request: Long): Unit = synchronized(LOCK) {
        if (prefs.getLong(KEY_REQUEST, 0) == request) prefs.edit().remove(KEY_PENDING).commit()
    }

    companion object {
        const val PREFS_FILE = "tigerduck_browser_cleanup"
        private const val KEY_PENDING = "pending"
        private const val KEY_REQUEST = "request"

        // Every instance's: they share the file.
        private val LOCK = Any()

        fun of(context: Context) = BrowserCleanup(context)
    }
}

/**
 * Clears everything the TigerDuck browser keeps about the student: the
 * WebView's stores, each site's zoom and desktop-site choice, and the list of
 * downloads (the files stay in the phone's Downloads). Run at every school
 * sign-in and sign-out, which a student switch and the demo account go
 * through, and by the full reset.
 */
class BrowserDataCleaner(
    private val storage: WebViewStorage,
    private val settings: BrowserSettings,
    private val downloads: BrowserDownloads,
    private val cleanup: BrowserCleanup,
    /** Where a clearing waits for deletions that report after it gave up on them. */
    private val scope: CoroutineScope,
) {
    /**
     * One clearing at a time. The session moves on as it starts, closing the
     * screens open, and again as it ends, closing one opened meanwhile, which
     * may have shown the departing student's pages before the cookies went.
     */
    suspend fun clear() = lock.withLock {
        val request = cleanup.markPending()
        BrowserSession.setClearing(true)
        try {
            BrowserSession.end()
            downloads.clear()
            settings.clearSites()
            val cleared = runCatching { storage.clear() }
                .onFailure { Log.w(TAG, "browser storage not cleared: ${it.javaClass.simpleName}") }
                .getOrNull()
            when {
                cleared?.inTime == true -> cleanup.markDone(request)
                // The browser stays closed till the late deletions report, and opens again then.
                cleared != null -> scope.launch { if (cleared.settled()) cleanup.markDone(request) }
                // The next start clears it again, and the browser stays closed till then.
                else -> Unit
            }
        } finally {
            BrowserSession.end()
            BrowserSession.setClearing(false)
        }
    }

    /**
     * The WebView's stores alone: cookies, site storage and the pages' cache, for Settings'
     * Storage page. The screens open close first, as for [clear], since what they show goes; the
     * downloads go on, and each site's zoom and desktop choice stay. Not an account change, so
     * nothing is marked to clear again at the next start: a clearing cut short leaves only some of
     * the user's own sites' data.
     */
    suspend fun clearWebsiteData() = lock.withLock {
        BrowserSession.setClearing(true)
        try {
            BrowserSession.end()
            runCatching { storage.clear() }
                .onFailure { Log.w(TAG, "browser storage not cleared: ${it.javaClass.simpleName}") }
        } finally {
            BrowserSession.end()
            BrowserSession.setClearing(false)
        }
    }

    private val lock = Mutex()

    private companion object {
        const val TAG = "BrowserData"
    }
}

/**
 * [WebViewStorage] for the app's WebViews, which share one profile: every
 * cookie (waited for, as the removal is asynchronous), web storage, saved
 * HTTP auth, and the disk cache, which a WebView clears for all of them. On
 * the main thread, as the WebView wants. The mail WebViews keep none of these.
 */
class AndroidWebViewStorage(private val context: Context) : WebViewStorage {
    override suspend fun clear(): StorageClearing = withContext(Dispatchers.Main) {
        val cookies = CookieManager.getInstance()
        var inTime = true
        val cookieRemoval = startDeletion { done -> cookies.removeAllCookies { done() } }
        if (!reportsInTime(cookieRemoval, CLEAR_TIMEOUT_MS)) {
            Log.w(TAG, "cookie removal did not report back yet")
            inTime = false
        }
        cookies.flush()
        // Everything a site can keep, service workers and Cache Storage too, where the WebView
        // can; deleteAllData below leaves those. The browser opens only where it can.
        val browsingData = if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            startDeletion { done -> WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) { done() } }
        } else {
            Log.w(TAG, "this WebView cannot delete service workers and Cache Storage")
            inTime = false
            null
        }
        if (browsingData != null && !reportsInTime(browsingData, CLEAR_TIMEOUT_MS)) {
            Log.w(TAG, "browsing data deletion did not report back yet")
            inTime = false
        }
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        WebView(context.applicationContext).apply {
            clearCache(true)
            destroy()
        }
        // Photos taken for a page's upload field.
        withContext(Dispatchers.IO) { java.io.File(context.cacheDir, UPLOADS_DIR).deleteRecursively() }
        StorageClearing(inTime) {
            // Without service workers and Cache Storage, never everything.
            browsingData != null && run {
                cookieRemoval.await()
                browsingData.await()
                // The flush above may have come before a cookie removal that reported late.
                withContext(Dispatchers.Main) { cookies.flush() }
                true
            }
        }
    }

    private companion object {
        const val TAG = "BrowserData"
        const val CLEAR_TIMEOUT_MS = 5_000L

        /** InAppBrowserActivity's, under the cache. */
        const val UPLOADS_DIR = "browser_uploads"
    }
}
