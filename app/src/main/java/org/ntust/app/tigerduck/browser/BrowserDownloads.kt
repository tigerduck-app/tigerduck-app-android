package org.ntust.app.tigerduck.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import org.ntust.app.tigerduck.shared.tls.pinnedClientBuilder

/** What the browser does with a file a page offers. */
sealed interface DownloadPlan {
    /** Saves it to the phone's Downloads as [fileName]. */
    data class Save(val url: String, val fileName: String, val mimeType: String) : DownloadPlan

    /** Not saved: off the allowlist, or not an https file the browser can fetch again (`blob:`, `data:`). */
    data object Refused : DownloadPlan
}

object BrowserDownloadPlans {
    private const val GENERIC_TYPE = "application/octet-stream"

    /**
     * The plan for a download of [url]: only an https file on a site the
     * allowlist lets in is saved, under the name [guessFileName] makes of it
     * (`URLUtil.guessFileName`) and the type the server gave, or the name's.
     */
    fun plan(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        allowlist: BrowserAllowlist,
        guessFileName: (url: String, contentDisposition: String?, mimeType: String?) -> String,
        mimeOfExtension: (String) -> String?,
    ): DownloadPlan {
        if (!allowlist.allows(url)) return DownloadPlan.Refused
        val given = mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != GENERIC_TYPE }
        val name = guessFileName(url, contentDisposition, given).trim().ifEmpty { "download" }
        val type = given ?: name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { mimeOfExtension(it.lowercase()) } ?: GENERIC_TYPE
        return DownloadPlan.Save(url, name, type)
    }
}

/** One file the browser downloaded, or is downloading, this session. */
data class BrowserDownload(
    val id: Long,
    val fileName: String,
    val mimeType: String,
    val bytes: Long = 0,
    /** Null while the size is not known. */
    val total: Long? = null,
    val state: State = State.RUNNING,
    /** The saved file, once [State.DONE]. */
    val uri: Uri? = null,
) {
    enum class State { RUNNING, DONE, FAILED, CANCELLED }
}

/** Fetches a file and saves it where the student can find it. */
fun interface DownloadTransfer {
    /** Saves [plan]'s file, reporting bytes as they come, and gives the saved file back. */
    suspend fun download(plan: DownloadPlan.Save, progress: (bytes: Long, total: Long?) -> Unit): Uri
}

/**
 * The files the browser downloads, in memory for this run of the app: the
 * list the ⋮ menu's "Downloaded files" shows. The files themselves go to the
 * phone's Downloads and stay there; [clear], at sign-out, empties the list
 * and stops any download still running.
 */
class BrowserDownloads(
    private val scope: CoroutineScope,
    private val transfer: DownloadTransfer,
) {
    private val ids = AtomicLong()
    private val jobs = mutableMapOf<Long, Job>()
    private val state = MutableStateFlow<List<BrowserDownload>>(emptyList())

    /** Newest first. */
    val items: StateFlow<List<BrowserDownload>> = state.asStateFlow()

    /**
     * Starts saving [plan]'s file, for a page of browser [session]; null,
     * saving nothing, while the browser is cleared ([BrowserSession.clearing])
     * or for a page from before a clearing: the departing student's, not yet
     * closed, may still ask. The download is registered before it runs, so a
     * clearing stops it whenever it began.
     */
    fun start(plan: DownloadPlan.Save, session: Int = BrowserSession.generation.value): Long? = synchronized(jobs) {
        if (BrowserSession.clearing.value || session != BrowserSession.generation.value) return null
        val id = ids.incrementAndGet()
        state.update { listOf(BrowserDownload(id, plan.fileName, plan.mimeType)) + it }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val uri = transfer.download(plan) { bytes, total ->
                    change(id) { if (it.state == BrowserDownload.State.RUNNING) it.copy(bytes = bytes, total = total) else it }
                }
                change(id) { it.copy(state = BrowserDownload.State.DONE, uri = uri) }
            } catch (e: CancellationException) {
                change(id) { it.copy(state = BrowserDownload.State.CANCELLED) }
                throw e
            } catch (_: Exception) {
                change(id) { it.copy(state = BrowserDownload.State.FAILED) }
            } finally {
                synchronized(jobs) { jobs.remove(id) }
            }
        }
        jobs[id] = job
        job.start()
        id
    }

    fun cancel(id: Long) {
        // Registered until it has stopped, which its finally says: a clearing waits for it till then.
        synchronized(jobs) { jobs[id] }?.cancel()
        change(id) { if (it.state == BrowserDownload.State.RUNNING) it.copy(state = BrowserDownload.State.CANCELLED) else it }
    }

    /** Stops every running download, waits for each to end, and forgets the list. */
    suspend fun clear() {
        val running = synchronized(jobs) { jobs.values.toList().also { jobs.clear() } }
        running.forEach { it.cancel() }
        running.joinAll()
        state.value = emptyList()
    }

    private fun change(id: Long, edit: (BrowserDownload) -> BrowserDownload) {
        state.update { list -> list.map { if (it.id == id) edit(it) else it } }
    }
}

/**
 * Fetches with a client of its own, which holds no cookies of the app's: each
 * request carries the browser's cookies for its own host ([cookieFor],
 * `CookieManager.getCookie`) and the browser's user agent. A redirect's
 * cookies go into the browser's ([keepCookie], `CookieManager.setCookie`)
 * before the next hop, as the browser keeps them when it follows a redirect
 * itself, so the WebView's own host, path and expiry rules decide what the
 * next hop sends. They cannot outlive a sign-out's clearing: each is set,
 * and done, inside the download, which the clearing stops and waits for
 * before it removes the browser's cookies. Nor one that leaves the download
 * running (Settings' website data): a cookie is kept only while the session
 * the download began in goes on, and then no more.
 * Redirects are followed here, one at a time, so a cookie never goes to a
 * host it is not for and a redirect off the allowlist stops the download.
 * The file is written to MediaStore's Downloads, pending until complete and
 * deleted when the download fails or is stopped.
 */
class MediaStoreDownloadTransfer(
    private val context: Context,
    private val allowlist: () -> BrowserAllowlist,
    private val cookieFor: (String) -> String?,
    /** Keeps a redirect's Set-Cookie value for the address that sent it, done before it returns. */
    private val keepCookie: (url: String, setCookie: String) -> Unit,
    private val userAgent: () -> String?,
    /** [client]'s, pinned, in the app; a test's own. */
    private val client: OkHttpClient,
    /** Where the body goes; MediaStore's Downloads, a stand-in in tests. */
    private val save: (suspend (DownloadPlan.Save, Long?, java.io.InputStream, (Long, Long?) -> Unit) -> Uri)? = null,
) : DownloadTransfer {

    override suspend fun download(plan: DownloadPlan.Save, progress: (Long, Long?) -> Unit): Uri = withContext(Dispatchers.IO) {
        val session = BrowserSession.generation.value
        var url = plan.url
        repeat(MAX_REDIRECTS + 1) {
            // A download stopped at a sign-out asks for nothing more, under anyone's cookies.
            currentCoroutineContext().ensureActive()
            if (!allowlist().allows(url)) throw IOException("download left the allowlist")
            val request = Request.Builder().url(url).apply {
                cookieFor(url)?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
                userAgent()?.let { header("User-Agent", it) }
            }.build()
            when (val hop = hop(client.newCall(request), plan, progress)) {
                is Hop.Redirect -> {
                    for (cookie in hop.cookies) {
                        // Stopped by a clearing meanwhile: nothing more goes into the browser's cookies.
                        currentCoroutineContext().ensureActive()
                        // Nor after one that let it run: the cookies it cleared stay gone.
                        BrowserSession.ifStill(session) { keepCookie(url, cookie) }
                    }
                    url = hop.url
                }
                is Hop.Saved -> return@withContext hop.uri
            }
        }
        throw IOException("too many redirects")
    }

    private sealed interface Hop {
        /** On to [url], with the redirect's Set-Cookie values. */
        data class Redirect(val url: String, val cookies: List<String>) : Hop
        data class Saved(val uri: Uri) : Hop
    }

    /**
     * One request: a redirect to follow, or the file saved. The call is stopped with the
     * download however far it got, its body's read included, which blocks until the call is.
     */
    private suspend fun hop(call: Call, plan: DownloadPlan.Save, progress: (Long, Long?) -> Unit): Hop = coroutineScope {
        val stopper = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.await().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                        ?: throw IOException("redirect without a location")
                    return@coroutineScope Hop.Redirect(next, response.headers("Set-Cookie"))
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body
                val total = body.contentLength().takeIf { it >= 0 }
                Hop.Saved((save ?: ::saveToDownloads)(plan, total, body.byteStream(), progress))
            }
        } finally {
            stopper.cancel()
        }
    }

    private suspend fun saveToDownloads(plan: DownloadPlan.Save, total: Long?, input: java.io.InputStream, progress: (Long, Long?) -> Unit): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, plan.fileName)
            put(MediaStore.Downloads.MIME_TYPE, plan.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("no Downloads entry")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    bytes += read
                    progress(bytes, total)
                }
            } ?: throw IOException("Downloads entry not writable")
            currentCoroutineContext().ensureActive()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** The call, stopped when the coroutine waiting on it is: a blocking execute() would run on. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, _, _ -> response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
    }

    companion object {
        private const val MAX_REDIRECTS = 10

        /**
         * The download client: the app's pinned trust, as every client's (TLS pins spec, A9),
         * though the browser's own WebView isn't pinned; redirects are followed by hand.
         */
        fun client(tls: PinnedTls): OkHttpClient = pinnedClientBuilder(tls)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
