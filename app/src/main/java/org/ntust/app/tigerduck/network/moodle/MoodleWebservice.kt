package org.ntust.app.tigerduck.network.moodle

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.NtustSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/** What `core_webservice_get_site_info` says about the account a token belongs to. */
data class MoodleSiteInfo(
    val userId: Int,
    /** The web-service functions the site lets this token call. A feature whose call is missing hides. */
    val functions: Set<String>,
    /** `userprivateaccesskey`, which file URLs carry in place of the token. A secret: never logged. */
    val privateAccessKey: String?,
)

/**
 * Every call to Moodle's REST web service goes through here: the request, the error mapping and
 * the token with its renewal, as iOS's `MoodleWebserviceClient` (its issue #260).
 *
 * Moodle sends most errors with a 200 and an `errorcode` body, so the body is read before the
 * status. A 503 with `Retry-After` is the maintenance page an upgrade serves for every request.
 */
@Singleton
class MoodleWebservice internal constructor(
    private val client: () -> OkHttpClient,
    private val tokens: TokenSource,
) {
    @Inject
    constructor(sessionManager: NtustSessionManager, tokenService: MoodleTokenService) : this(
        client = { sessionManager.client },
        tokens = object : TokenSource {
            override fun stored(): String? = tokenService.currentToken()?.takeIf { it.isNotEmpty() }
            override suspend fun renew(rejected: String?): String = tokenService.renewToken(rejected)
        },
    )

    /** Where [withToken] reads and renews the token. Tests pass stand-ins. */
    interface TokenSource {
        fun stored(): String?

        /** A token in place of [rejected], which is null when none was stored. */
        suspend fun renew(rejected: String?): String
    }

    /**
     * The token [siteInfo] last answered for, with its answer; another token asks again. Read
     * outside the lock on the fast path, so written where every thread sees it at once.
     */
    @Volatile
    private var siteInfoCache: Pair<String, MoodleSiteInfo>? = null
    private val siteInfoLock = Mutex()

    /**
     * Runs [body] with the stored token, or a renewed one when none is stored. Once Moodle calls
     * that token invalid, a renewed one replaces it and [body] runs once more. A call that needs
     * the token's user id asks [siteInfo] inside [body], so a stale token there is renewed too.
     *
     * An access error gets one more try with the same token: Moodle answers an expired token with
     * `accessexception` once and deletes it, then answers `invalidtoken`. A function the site does
     * not allow fails the same way twice and starts no renewal.
     */
    suspend fun <T> withToken(body: suspend (token: String) -> T): T {
        val token = tokens.stored() ?: tokens.renew(null)
        return try {
            try {
                body(token)
            } catch (_: MoodleWebserviceError.AccessDenied) {
                body(token)
            }
        } catch (_: MoodleWebserviceError.InvalidToken) {
            body(tokens.renew(token))
        }
    }

    /** [function]'s answer under [token], as JSON. Throws [MoodleWebserviceError] for Moodle's errors. */
    suspend fun call(
        function: String,
        token: String,
        params: List<Pair<String, String>> = emptyList(),
    ): JsonElement = withContext(Dispatchers.IO) {
        val body = execute(client(), request(function, token, params))
        try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            throw MoodleWebserviceError.MalformedResponse("$function not JSON: ${e.javaClass.simpleName}")
        }
    }

    /**
     * The bytes [request] downloads, when the reply's type passes [accepts]. A Moodle error,
     * which comes as JSON, throws its own case; a page standing in for the file, such as a
     * sign-in page, throws [MoodleWebserviceError.MalformedResponse]. Blocking: call on IO.
     */
    fun download(request: Request, accepts: (contentType: String?) -> Boolean): ByteArray =
        client().newCall(request).execute().use { response ->
            // The bare type: Moodle sends its errors as `application/json; charset=utf-8`.
            val type = baseType(response.body.contentType()?.toString() ?: response.header("Content-Type"))
            if (type == "application/json") {
                // No further than an error reply runs: a reply that never ends is not read whole.
                MoodleWebserviceError.fromJsonBody(response.peekBody(ERROR_REPLY_LIMIT).string())?.let { throw it }
                throw MoodleWebserviceError.MalformedResponse("json in place of a file")
            }
            if (response.code != 200) throw MoodleWebserviceError.HttpStatus(response.code)
            if (!accepts(type)) throw MoodleWebserviceError.MalformedResponse(type.orEmpty())
            response.body.bytes()
        }

    /**
     * Streams [request]'s file into [target], at most [maxBytes] of it. A Moodle error, which comes
     * as JSON whatever its length is said to be, throws its own case, so a dead token starts a
     * renewal; anything other than the file Moodle listed, such as a sign-in page, or more than
     * [maxBytes], throws [MoodleWebserviceError.MalformedResponse]. With no [expectedMime] to check,
     * [refusesPage] refuses a web page in its place. [onProgress] gets the bytes read so far and
     * the length the reply gives, -1 without one. Cancelling the caller cancels the request,
     * which a blocking read would otherwise sit out.
     */
    suspend fun downloadTo(
        request: Request,
        target: java.io.File,
        expectedMime: String?,
        maxBytes: Long,
        refusesPage: Boolean = false,
        onProgress: (read: Long, length: Long) -> Unit = { _, _ -> },
    ) {
        val call = client().newCall(request)
        coroutineScope {
            val cancelOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) { readFile(call, target, expectedMime, maxBytes, refusesPage, onProgress) }
            } catch (e: java.io.IOException) {
                // The request cancelled because its caller was: that is the cancellation, not a
                // network failure to report.
                currentCoroutineContext().ensureActive()
                throw e
            } finally {
                cancelOnCancel.cancel()
            }
        }
    }

    private suspend fun readFile(
        call: okhttp3.Call,
        target: java.io.File,
        expectedMime: String?,
        maxBytes: Long,
        refusesPage: Boolean,
        onProgress: (Long, Long) -> Unit,
    ) {
        call.execute().use { response ->
            val type = response.body.contentType()?.toString() ?: response.header("Content-Type")
            if (baseType(type) == "application/json" || type == null) {
                MoodleWebserviceError.fromJsonBody(response.peekBody(ERROR_REPLY_LIMIT).string())?.let { throw it }
            }
            checkFile(response.code, type, expectedMime)
            if (refusesPage && expectedMime == null && baseType(type) in PAGE_TYPES) {
                throw MoodleWebserviceError.MalformedResponse(baseType(type).orEmpty())
            }
            val length = response.body.contentLength()
            if (length > maxBytes) throw MoodleWebserviceError.MalformedResponse("larger than listed")
            val source = response.body.source()
            target.outputStream().use { out ->
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw MoodleWebserviceError.MalformedResponse("larger than listed")
                    out.write(chunk, 0, read)
                    onProgress(total, length)
                }
            }
        }
    }

    /**
     * Uploads [bytes] as [fileName] ([mimeType]) to a new draft area of [token]'s account through
     * Moodle's `webservice/upload.php`, the token in the form, and returns the area's item id, which
     * a function such as `core_user_update_picture` takes. Throws [MoodleWebserviceError] for
     * Moodle's errors, which upload.php answers as the functions do.
     */
    suspend fun uploadDraft(token: String, fileName: String, mimeType: String, bytes: ByteArray): Long =
        withContext(Dispatchers.IO) {
            val form = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("token", token)
                .addFormDataPart("filearea", "draft")
                // A new draft area, whose id the answer gives.
                .addFormDataPart("itemid", "0")
                .addFormDataPart("file", fileName, bytes.toRequestBody(mimeType.toMediaType()))
                .build()
            val body = execute(client(), Request.Builder().url(UPLOAD_URL).post(form).build())
            val json = try {
                JsonParser.parseString(body)
            } catch (e: Exception) {
                throw MoodleWebserviceError.MalformedResponse("upload not JSON: ${e.javaClass.simpleName}")
            }
            MoodleJson.draftItemId(json)
        }

    /** The site info for [token], asked once per token. Concurrent callers share one request. */
    suspend fun siteInfo(token: String): MoodleSiteInfo {
        siteInfoCache?.takeIf { it.first == token }?.let { return it.second }
        return siteInfoLock.withLock {
            siteInfoCache?.takeIf { it.first == token }?.let { return@withLock it.second }
            val info = MoodleJson.siteInfo(call(SITE_INFO_FUNCTION, token))
            siteInfoCache = token to info
            info
        }
    }

    companion object {
        const val SITE = "https://moodle2.ntust.edu.tw"
        const val SITE_HOST = "moodle2.ntust.edu.tw"
        const val WEBSERVICE_URL = "$SITE/webservice/rest/server.php"
        const val UPLOAD_URL = "$SITE/webservice/upload.php"
        const val SITE_INFO_FUNCTION = "core_webservice_get_site_info"

        /** The web-service URL for [function] under [token]; the parameters go in the form body. */
        fun url(function: String, token: String): String =
            "$WEBSERVICE_URL?moodlewsrestformat=json&wsfunction=$function&wstoken=$token"

        internal fun request(function: String, token: String, params: List<Pair<String, String>>): Request {
            val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
            return Request.Builder().url(url(function, token)).post(form).build()
        }

        /**
         * Runs [request] through [client] and returns the body of an answer, or throws: Moodle's
         * own error from the body whatever the status, [MoodleWebserviceError.SiteMaintenance]
         * for the maintenance page, [MoodleWebserviceError.HttpStatus] for any other failure.
         * A request that never got an answer throws its `IOException`.
         */
        fun execute(client: OkHttpClient, request: Request): String =
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                checkAnswer(response.code, response.header("Retry-After"), body)
                body
            }

        /** What a sign-in or error page is served as. */
        private val PAGE_TYPES = setOf("text/html", "application/xhtml+xml")

        /** A Moodle error is a few hundred bytes, so a longer JSON reply is the file itself. */
        private const val ERROR_REPLY_LIMIT = 64 * 1024L

        /** `"Application/PDF; name=a.pdf"` -> `"application/pdf"`. */
        private fun baseType(raw: String?): String? =
            raw?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        /**
         * Moodle serves a stored file with the MIME type it lists for it, so another type is a
         * page standing in for the file.
         */
        internal fun checkFile(code: Int, contentType: String?, expectedMime: String?) {
            if (code != 200) throw MoodleWebserviceError.HttpStatus(code)
            val expected = baseType(expectedMime) ?: return
            val served = baseType(contentType)
            if (served != expected) throw MoodleWebserviceError.MalformedResponse(served.orEmpty())
        }

        internal fun checkAnswer(code: Int, retryAfter: String?, body: String) {
            MoodleWebserviceError.fromJsonBody(body)?.let { throw it }
            if (code == 503 && retryAfter != null) throw MoodleWebserviceError.SiteMaintenance()
            if (code !in 200..299) throw MoodleWebserviceError.HttpStatus(code)
        }
    }
}
