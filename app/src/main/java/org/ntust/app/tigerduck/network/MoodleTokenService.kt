package org.ntust.app.tigerduck.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.CoroutineScope
import org.ntust.app.tigerduck.auth.SIGN_IN_CALL_TIMEOUT_SECONDS
import org.ntust.app.tigerduck.auth.SIGN_IN_PAUSE_MS
import org.ntust.app.tigerduck.auth.PasswordPosting
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.shared.CredentialPost
import org.ntust.app.tigerduck.shared.executeCredentialPost
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Obtains and persists a Moodle Mobile App long-lived wstoken via the NTUST
 * OIDC launch flow, mirroring the verified Moodle iOS HAR trace.
 *
 * WARNING — do NOT replace this flow with a POST to `/login/token.php`.
 * NTUST Moodle authenticates via OIDC only; the local-password token
 * endpoint counts every attempt as a failed login and triggers
 * `login_lockout`, banning the account within ~10 attempts.
 *
 * Flow:
 *   [1] GET  /admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=...&urlscheme=moodlemobile
 *   [2] OkHttp auto-follows redirects → ssoam2/account/login
 *   [3] Parse SSO form, POST credentials to ssoam2
 *   [4] Auto-follow → OIDC bridge form (code/state/iss)
 *   [5] POST bridge to /auth/oidc/
 *   [6] Auto-follow → launch.php?confirmed=... which emits `moodlemobile://token=BASE64`
 *   [7] Base64-decode token payload (usually
 *       `signature:::wstoken:::privatetoken`)
 */
@Singleton
class MoodleTokenService internal constructor(
    /** The client the OIDC flow builds on; the session manager's, so it shares its cookie jar. */
    private val clientFor: () -> OkHttpClient,
    /**
     * Where the tokens and the account they are harvested with are stored: the wstoken, and the
     * private token kept beside it; see [MoodleTokenPairs].
     */
    tokenStore: MoodleTokenPairStore,
    passwordLatch: StoredPasswordLatch,
    scope: CoroutineScope,
    /** A jar for one sign-in to Moodle's website, its own; see [signInToWebsite]. */
    private val newCookieJar: () -> SignInCookieJar = { SignInCookieJar() },
    now: () -> Long,
) {
    @Inject
    constructor(
        sessionManager: NtustSessionManager,
        credentialManager: CredentialManager,
        @ApplicationScope appScope: CoroutineScope,
        passwordLatch: StoredPasswordLatch,
    ) : this(
        clientFor = { sessionManager.client },
        tokenStore = object : MoodleTokenPairStore {
            override var token: String?
                get() = credentialManager.moodleToken
                set(value) {
                    credentialManager.moodleToken = value
                }
            override var privateToken: String?
                get() = credentialManager.moodlePrivateToken
                set(value) {
                    credentialManager.moodlePrivateToken = value
                }
            override val studentId: String? get() = credentialManager.ntustStudentId
            override val password: String? get() = credentialManager.ntustPassword
            override fun storeIfSignedIn(studentId: String, token: String, privateToken: String?): Boolean =
                credentialManager.storeMoodleTokenIfSignedIn(studentId, token, privateToken)
        },
        passwordLatch = passwordLatch,
        scope = appScope,
        now = System::currentTimeMillis,
    )

    private val moodleBaseUrl = "https://moodle2.ntust.edu.tw".toHttpUrl()

    private val moodleUa =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 " +
                "MoodleMobile 5.1.1 (51100)"

    /**
     * Dedicated client that forces the Moodle Mobile UA on every hop of the
     * OIDC chain (SSO portal + Moodle). The session manager's default
     * interceptor only swaps UA for moodle2.* — SSO hops need it too so
     * NetScaler doesn't serve an anti-bot challenge mid-redirect. Each call
     * has a deadline, as the automatic backend sign-in waits on a renewal;
     * see SIGN_IN_CALL_TIMEOUT_SECONDS.
     */
    private val tokenClient: OkHttpClient by lazy {
        clientFor().newBuilder()
            .callTimeout(SIGN_IN_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", moodleUa)
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    )
                    .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    fun currentToken(): String? = tokens.token

    /** The private token is kept beside the wstoken it came with; see [MoodleTokenPairs]. */
    private val tokens = MoodleTokenPairs(tokenStore)

    /** The stored private token, which only asks Moodle for an autologin key. Never logged. */
    fun currentPrivateToken(): String? = tokens.privateToken

    /**
     * Signs in again for the private token the stored wstoken came without, as 2.3.1 and earlier
     * kept no private token. It is a renewal of the stored token, so it shares a sign-in already
     * under way and holds back as every renewal does: after one that failed for a reason other
     * than the network, and while the stored password is refused, posting nothing. A failure
     * leaves the stored token as it was. True once a private token is stored; false with no
     * wstoken stored, or when Moodle hands the pair over without a private token.
     */
    suspend fun fetchPrivateToken(): Boolean {
        if (currentPrivateToken() != null) return true
        val token = currentToken() ?: return false
        renewToken(token)
        return currentPrivateToken() != null
    }

    private val coordinator = MoodleTokenCoordinator(
        tokens = tokens,
        login = { studentId, password, posting ->
            val triple = performOidcLogin(studentId, password, posting)
            tokens.harvested(triple.wstoken, triple.privatetoken)
            triple.wstoken.also {
                Log.i("MoodleTokenService", "harvested wstoken (len=${it.length})")
            }
        },
        scope = scope,
        now = now,
        pauseMs = SIGN_IN_PAUSE_MS,
        passwordLatch = passwordLatch,
    )

    /**
     * A token in place of [rejected], which is null when none was stored, for a Moodle call that
     * found its token dead. The stored token is replaced only once a new one is in hand, so a
     * renewal that fails, as during Moodle maintenance, leaves the old one rather than none.
     * Concurrent callers share one SSO sign-in, a failed one pauses the next, and credentials SSO
     * refused are not sent again until they change; see [MoodleTokenRenewal].
     */
    suspend fun renewToken(rejected: String?): String = coordinator.renew(rejected)

    /**
     * Signs in to Moodle's website with the school SSO session the app already holds, as the
     * iPhone's browser does, and hands back the session's cookies: Moodle's alone, for the
     * TigerDuck browser when Moodle has no autologin key for it. No password is posted, and none
     * is filled into a page: SSO asking for one ends it ([SsoSessionMissing]). Throws when no
     * session can be had; a sign-out meanwhile leaves none of it.
     */
    suspend fun signInToWebsite(): List<Cookie> = withContext(Dispatchers.IO) { walkWebsiteSignIn(RunningCalls()) }

    /**
     * Fetch a fresh token using the supplied credentials (first-time login). It is stored only
     * while [studentId] is still the stored account, so a sign-out during the sign-in leaves none
     * of the departing student's; then this throws.
     */
    suspend fun obtainToken(studentId: String, password: String): String = coordinator.obtain(studentId, password)

    private data class TokenTriple(
        val signature: String,
        val wstoken: String,
        val privatetoken: String?,
    )

    /** [posting] posts the password to SSO and reads its answer; it may refuse to post it. */
    private suspend fun performOidcLogin(
        studentId: String,
        password: String,
        posting: PasswordPosting,
    ): TokenTriple = withContext(Dispatchers.IO) {
        // The exact string form sent to the server is what Moodle HMACs against,
        // so keep this one copy and reuse it for both the query param and the
        // signature check after the final token triple arrives.
        // 16 SecureRandom bytes (128 bits) hex-encoded — the prior
        // Random.nextDouble * 1000 produced ~10 bits, brute-forceable in ms by
        // a malicious moodlemobile:// scheme registrant.
        val passport = ByteArray(16)
            .also { SecureRandom().nextBytes(it) }
            .joinToString(separator = "") { "%02x".format(it) }
        val launchUrl = moodleBaseUrl
            .newBuilder()
            .addPathSegments("admin/tool/mobile/launch.php")
            .addQueryParameter("service", "moodle_mobile_app")
            .addQueryParameter("passport", passport)
            .addQueryParameter("urlscheme", "moodlemobile")
            .build()
        // `launchUrl` carries the `passport` HMAC key in its query; never log the
        // full URL. Emit a redacted hint instead.
        Log.i("MoodleTokenService", "launch GET ${launchUrl.redactForLog()}")
        val (html, finalUrl) = getPage(tokenClient, launchUrl)
        // Terminal state: the mobile launch confirmation page embeds a
        // custom-scheme URL `moodlemobile://token=BASE64`. That's our token.
        walkOidc(
            client = tokenClient,
            html = html,
            responseUrl = finalUrl,
            studentId = studentId,
            password = password,
            posting = posting,
            remainingSteps = 6,
            arrived = { page, _ -> extractMoodleMobileToken(page)?.let(::decodeTokenTriple) },
        )
    }

    /**
     * Signs in to Moodle's website: Moodle's OIDC entry, the school's SSO, the bridge back to
     * Moodle, in a jar of this run's own ([newCookieJar]) that starts with the SSO session of the
     * app's shared jar, the session manager's. SSO, holding its session, answers with the bridge
     * at once; asking for the password instead, or anything else, ends the run with nothing posted.
     * Done once it lands on a Moodle page past its sign-in, and only once a page no one signed out
     * gets ([SIGNED_IN_CHECK]) opens there rather than sending it back to sign in. Moodle's
     * session cookies are what it hands back; nothing goes back into the shared jar.
     */
    private suspend fun walkWebsiteSignIn(calls: RunningCalls): List<Cookie> {
        val account = tokens.studentId ?: throw MoodleWebserviceError.MissingStoredCredentials()
        val jar = newCookieJar()
        jar.saveFromResponse(SSO_SITE, clientFor().cookieJar.loadForRequest(SSO_SITE))
        val client = tokenClient.newBuilder()
            .cookieJar(jar)
            // Each call as it starts, whatever its interceptors do with it.
            .eventListener(calls)
            .build()
        val entry = moodleBaseUrl.resolve(WEBSITE_SIGN_IN_ENTRY)!!
        Log.i("MoodleTokenService", "website sign-in GET ${entry.redactForLog()}")
        var (html, url) = awaitApart(calls::cancelAll) { getPage(client, entry) }
        var steps = 0
        while (!isPastMoodleSignIn(url)) {
            if (++steps > WEBSITE_SIGN_IN_STEPS) throw MoodleWebserviceError.MalformedResponse("OIDC flow exceeded maximum step count")
            // SSO's sign-in form, a challenge, a page of its own: no session the app can use.
            val bridge = parseOIDCBridge(html) ?: throw SsoSessionMissing()
            // As the token's walk reads it: a path alone is Moodle's.
            val target = (if (bridge.action.startsWith("/")) moodleBaseUrl.resolve(bridge.action) else url.resolve(bridge.action))
                ?: throw MoodleWebserviceError.MalformedResponse("Invalid bridge action: ${bridge.action}")
            // The code passes the sign-in on to Moodle, so it goes to the school's hosts over HTTPS
            // and nowhere else.
            if (!CredentialPost.isSchoolTarget(target)) {
                throw MoodleWebserviceError.MalformedResponse("OIDC bridge action outside the school's HTTPS hosts")
            }
            Log.i("MoodleTokenService", "posting OIDC bridge → ${target.redactForLog()}")
            val referer = url.toString()
            val next = awaitApart(calls::cancelAll) { postForm(client, target, bridge.payload, referer = referer) }
            html = next.first
            url = next.second
        }
        val (editor, checked) = awaitApart(calls::cancelAll) { getPage(client, moodleBaseUrl.resolve(SIGNED_IN_CHECK)!!) }
        if (!checked.host.equals(moodleBaseUrl.host, ignoreCase = true) ||
            checked.encodedPath != SIGNED_IN_CHECK ||
            !isSignedInEditor(editor)
        ) {
            throw MoodleWebserviceError.MalformedResponse("Moodle's website did not keep the session")
        }
        // Signed out, or signed in as another student, while it ran: the session is the departing one's.
        if (tokens.studentId != account) throw MoodleWebserviceError.MissingStoredCredentials()
        return jar.loadForRequest(moodleBaseUrl)
            .filter { it.name.startsWith(MOODLE_SESSION_COOKIE) || it.name.startsWith(MOODLE_ID_COOKIE) }
            .takeIf { cookies -> cookies.any { it.name.startsWith(MOODLE_SESSION_COOKIE) } }
            ?: throw MoodleWebserviceError.MalformedResponse("Moodle set no session cookie")
    }

    /**
     * The calls one sign-in has started, to cancel together; one started after [cancelAll] is
     * cancelled as it starts. Told of each as it starts ([callStart]), once the call is whole.
     */
    private class RunningCalls : EventListener() {
        private val calls = mutableListOf<Call>()
        private var cancelled = false

        @Synchronized
        override fun callStart(call: Call) {
            if (cancelled) call.cancel() else calls += call
        }

        @Synchronized
        fun cancelAll() {
            cancelled = true
            calls.forEach(Call::cancel)
        }
    }

    /**
     * Whether [html] is Moodle's profile editor as a signed-in student gets it: the page Moodle names
     * `page-user-edit`, with its form back to the editor carrying the session key, and not marked
     * signed out. Anything else at the editor's address (a challenge, a gateway's page) is not.
     */
    private fun isSignedInEditor(html: String): Boolean =
        EDITOR_BODY.containsMatchIn(html) &&
            EDITOR_FORM.containsMatchIn(html) &&
            html.contains("name=\"sesskey\"") &&
            !NOT_LOGGED_IN_BODY.containsMatchIn(html)

    /** A page on Moodle past its sign-in: not under `/login/`, nor its sign-in entries under `/auth/`. */
    private fun isPastMoodleSignIn(url: HttpUrl): Boolean {
        if (!url.host.equals(moodleBaseUrl.host, ignoreCase = true)) return false
        val path = url.encodedPath.lowercase()
        return !path.startsWith("/login/") && !path.startsWith("/auth/")
    }

    /**
     * Walks the school's OIDC sign-in from the page [html] at [responseUrl] with [client]: posts the
     * SSO form under [posting], then the bridge back to Moodle, till [arrived] says a page is the
     * end, whose answer it returns.
     */
    private suspend fun <T> walkOidc(
        client: OkHttpClient,
        html: String,
        responseUrl: HttpUrl,
        studentId: String,
        password: String,
        posting: PasswordPosting,
        remainingSteps: Int,
        arrived: (html: String, url: HttpUrl) -> T?,
    ): T {
        if (remainingSteps <= 0) {
            throw MoodleWebserviceError.MalformedResponse("OIDC flow exceeded maximum step count")
        }

        arrived(html, responseUrl)?.let { return it }

        // Step hop: Moodle's /auth/oidc/ bridge form carrying (code, state, iss)
        // from the OIDC IdP back to Moodle.
        parseOIDCBridge(html)?.let { bridge ->
            val url = when {
                bridge.action.startsWith("/") ->
                    moodleBaseUrl.resolve(bridge.action)
                        ?: throw MoodleWebserviceError.MalformedResponse("Invalid bridge action: ${bridge.action}")

                else -> responseUrl.resolve(bridge.action)
                    ?: throw MoodleWebserviceError.MalformedResponse("Invalid bridge action: ${bridge.action}")
            }
            // The code passes the sign-in on to Moodle, so it goes to the school's hosts over HTTPS
            // and nowhere else. Nothing is posted, so this is no refusal of the password.
            if (!CredentialPost.isSchoolTarget(url)) {
                throw MoodleWebserviceError.MalformedResponse("OIDC bridge action outside the school's HTTPS hosts")
            }
            Log.i("MoodleTokenService", "posting OIDC bridge → ${url.redactForLog()}")
            val (nextHtml, nextUrl) = postForm(
                client,
                url,
                bridge.payload,
                referer = responseUrl.toString()
            )
            return walkOidc(client, nextHtml, nextUrl, studentId, password, posting, remainingSteps - 1, arrived)
        }

        // SSO login step: we landed on NTUST's SSO front door with a form. (Which hostname
        // varies per account/service — see HtmlParser.isSsoHost.)
        if (HtmlParser.isSsoHost(responseUrl.host)) {
            val fields = parseSSOLoginFields(html)
            if (fields.antiforgery.isEmpty()) {
                if (responseUrl.encodedPath.contains("/account/login")) {
                    // Nothing was posted, so this is no refusal of the password.
                    throw MoodleWebserviceError.SsoLoginFormUnavailable(extractLoginError(html))
                }
                throw MoodleWebserviceError.MalformedResponse("SSO login form missing anti-forgery token")
            }
            val postUrl = responseUrl.resolve(fields.action)
                ?: throw MoodleWebserviceError.MalformedResponse("Invalid SSO action: ${fields.action}")
            // The credentials go to the school's sign-in host and nowhere else.
            if (!isSignInHost(postUrl)) {
                throw MoodleWebserviceError.MalformedResponse("SSO action outside the sign-in host")
            }
            val payload = linkedMapOf(
                "__RequestVerificationToken" to fields.antiforgery,
                "Username" to studentId,
                "Password" to password,
                "captcha" to "",
                "cf-turnstile-response" to "",
                "h-captcha-response" to "",
                "g-recaptcha-response" to "",
                "ClientId" to fields.clientId,
                "ReturnUrl" to fields.returnUrl,
                "Uri" to fields.uri,
            )
            // The post and SSO's answer to it are one step for the latch: an automatic harvest
            // posts under the lock every automatic post takes, and latches a refusal before it
            // lets go.
            val (nextHtml, nextUrl) = posting.post {
                Log.i("MoodleTokenService", "posting SSO login → ${postUrl.redactForLog()}")
                val answer = postLoginForm(
                    client = client,
                    url = postUrl,
                    fields = payload,
                    referer = responseUrl.toString(),
                    origin = "https://ssoam2.ntust.edu.tw",
                )
                // If we're still at /account/login and neither a token nor an OIDC
                // bridge is in sight, the credentials were rejected.
                if (answer.second.encodedPath.contains("/account/login") &&
                    extractMoodleMobileToken(answer.first) == null &&
                    parseOIDCBridge(answer.first) == null
                ) {
                    throw MoodleWebserviceError.SsoLoginRejected(extractLoginError(answer.first))
                }
                answer
            }
            return walkOidc(client, nextHtml, nextUrl, studentId, password, posting, remainingSteps - 1, arrived)
        }

        // Redact secrets before they hit logs/crash reports: the URL goes
        // through `redactForLog()` (masks passport/token/code/state query
        // params) and the HTML preview gets any input value of length ≥8
        // collapsed to `value="***"` to scrub OIDC `code`/`state`/session
        // values that ride hidden form fields between hops.
        val safePreview = html.take(300)
            .replace(Regex("value=\"[^\"]{8,}\""), "value=\"***\"")
            .replace(Regex("value='[^']{8,}'"), "value=\"***\"")
            .replace("\n", " ")
        throw MoodleWebserviceError.MalformedResponse(
            "Unexpected OIDC page: ${responseUrl.redactForLog()} — body preview=$safePreview"
        )
    }

    // --- HTTP helpers ---

    private fun getPage(client: OkHttpClient, url: HttpUrl): Pair<String, HttpUrl> {
        val req = Request.Builder().url(url).get().build()
        return executeAndReadBody(client, req)
    }

    /**
     * The OIDC bridge's POST, which carries the code SSO hands Moodle: no redirect sends the form
     * on, and one that would ends the sign-in ([executeCredentialPost]).
     */
    private fun postForm(
        client: OkHttpClient,
        url: HttpUrl,
        fields: Map<String, String>,
        referer: String? = null,
        origin: String? = null,
    ): Pair<String, HttpUrl> {
        val body = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        val reqBuilder = Request.Builder().url(url).post(body)
        if (referer != null) reqBuilder.header("Referer", referer)
        if (origin != null) reqBuilder.header("Origin", origin)
        return readBody(
            execute { client.executeCredentialPost(reqBuilder.build()) }
                ?: throw MoodleWebserviceError.MalformedResponse("OIDC bridge redirect not followed"),
        )
    }

    /**
     * The SSO sign-in form's POST, which carries the password: no redirect sends the form on to
     * another address, and one that would ends the sign-in ([executeSignInFormPost]).
     */
    private fun postLoginForm(
        client: OkHttpClient,
        url: HttpUrl,
        fields: Map<String, String>,
        referer: String,
        origin: String,
    ): Pair<String, HttpUrl> {
        val body = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder().url(url).post(body)
            .header("Referer", referer)
            .header("Origin", origin)
            .build()
        return readBody(
            execute { client.executeSignInFormPost(request) }
                ?: throw MoodleWebserviceError.MalformedResponse("SSO sign-in redirect not followed"),
        )
    }

    private fun executeAndReadBody(client: OkHttpClient, request: Request): Pair<String, HttpUrl> =
        readBody(execute { client.newCall(request).execute() })

    private fun <T> execute(call: () -> T): T = try {
        call()
    } catch (e: SocketTimeoutException) {
        throw MoodleWebserviceError.TransientNetwork("timeout: ${e.message}")
    } catch (e: java.io.IOException) {
        throw MoodleWebserviceError.TransientNetwork(e.message ?: "io error")
    }

    private fun readBody(response: Response): Pair<String, HttpUrl> {
        response.use { r ->
            if (!r.isSuccessful) {
                throw MoodleWebserviceError.HttpStatus(r.code)
            }
            val body = r.body.string()
            return body to r.request.url
        }
    }

    // --- HTML parsing ---

    private data class SSOLoginFields(
        val action: String,
        val antiforgery: String,
        val clientId: String,
        val returnUrl: String,
        val uri: String,
    )

    private data class OIDCBridge(val action: String, val payload: Map<String, String>)

    private val formRegex = Regex("<form[^>]*>([\\s\\S]+?)</form>", RegexOption.IGNORE_CASE)
    private val formActionRegex =
        Regex("<form[^>]*action=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val inputTagRegex = Regex("<input[^>]*>", RegexOption.IGNORE_CASE)
    private val inputNameRegex = Regex("name=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val inputValueRegex = Regex("value=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val moodleMobileTokenRegex =
        Regex("moodlemobile://token=([A-Za-z0-9+/=_\\-]+)")

    private fun parseSSOLoginFields(html: String): SSOLoginFields {
        for (formMatch in formRegex.findAll(html)) {
            val formFull = formMatch.value
            val formBody = formMatch.groupValues[1]
            val names = extractInputNames(formBody)
            if ("Username" in names && "Password" in names) {
                val action = extractFormAction(formFull) ?: "/"
                val fields = extractInputPairs(formBody)
                return SSOLoginFields(
                    action = action,
                    antiforgery = fields["__RequestVerificationToken"] ?: "",
                    clientId = fields["ClientId"] ?: "",
                    returnUrl = fields["ReturnUrl"] ?: "",
                    uri = fields["Uri"] ?: "",
                )
            }
        }
        return SSOLoginFields("/", "", "", "", "")
    }

    private fun parseOIDCBridge(html: String): OIDCBridge? {
        for (formMatch in formRegex.findAll(html)) {
            val formFull = formMatch.value
            val formBody = formMatch.groupValues[1]
            val action = extractFormAction(formFull) ?: continue
            val payload = extractInputPairs(formBody)
            val isOidcAction = action.contains("/auth/oidc")
            if (isOidcAction &&
                payload["code"] != null &&
                payload["state"] != null &&
                payload["iss"] != null
            ) {
                return OIDCBridge(action, payload)
            }
        }
        return null
    }

    private fun extractFormAction(formTag: String): String? =
        formActionRegex.find(formTag)?.groupValues?.getOrNull(1)?.let(::decodeHtmlEntities)

    private fun extractInputNames(html: String): Set<String> =
        inputTagRegex.findAll(html).mapNotNull { match ->
            inputNameRegex.find(match.value)?.groupValues?.getOrNull(1)
        }.toSet()

    private fun extractInputPairs(html: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (match in inputTagRegex.findAll(html)) {
            val tag = match.value
            val name = inputNameRegex.find(tag)?.groupValues?.getOrNull(1) ?: continue
            val value = inputValueRegex.find(tag)?.groupValues?.getOrNull(1) ?: ""
            out[name] = decodeHtmlEntities(value)
        }
        return out
    }

    private fun extractMoodleMobileToken(html: String): String? =
        moodleMobileTokenRegex.find(html)?.groupValues?.getOrNull(1)

    private fun decodeTokenTriple(base64: String): TokenTriple {
        val decoded = try {
            // UTF-8 is a strict superset of ASCII for current Moodle payloads;
            // US_ASCII would silently turn any byte > 0x7F into '?' if the
            // server ever changed shape.
            String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            throw MoodleWebserviceError.MalformedResponse("Failed to base64-decode token triple: ${e.message}")
        }
        // NTUST occasionally returns a 2-part payload. Accept both 2/3-part
        // shapes to keep the login flow resilient.
        val parts = decoded.split(":::", limit = 3)
        if (parts.size !in 2..3) {
            throw MoodleWebserviceError.MalformedResponse("Unexpected token triple (${parts.size} parts)")
        }
        return TokenTriple(
            signature = parts[0],
            wstoken = parts[1],
            privatetoken = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
        )
    }

    private fun extractLoginError(html: String): String? = HtmlParser.loginErrorMessage(html)

    // Named `redactForLog` so it doesn't shadow OkHttp's HttpUrl.redact() member.
    private fun HttpUrl.redactForLog(): String {
        val sensitive = setOf("passport", "wstoken", "token", "code", "state")
        val builder = newBuilder()
        for (name in queryParameterNames) {
            if (name.lowercase() in sensitive) {
                builder.setQueryParameter(name, "***")
            }
        }
        return builder.build().toString()
    }

    private fun decodeHtmlEntities(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#x2F;", "/")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")

    private companion object {
        /** Moodle's OIDC entry, where its website sends a student to the school's SSO. */
        const val WEBSITE_SIGN_IN_ENTRY = "/auth/oidc/?source=loginpage"

        /** A page Moodle shows only a signed-in student, and sends anyone else to sign in from. */
        const val SIGNED_IN_CHECK = "/user/edit.php"

        /** The bridge posts a sign-in to Moodle's website may take: SSO's form_post, and one to spare. */
        const val WEBSITE_SIGN_IN_STEPS = 2

        /** The school SSO's sign-in host, whose session the app's shared jar keeps. */
        val SSO_SITE = "https://ssoam2.ntust.edu.tw/".toHttpUrl()

        /** Moodle's session cookie, with the site's suffix if it sets one, and its remembered username. */
        const val MOODLE_SESSION_COOKIE = "MoodleSession"
        const val MOODLE_ID_COOKIE = "MOODLEID1_"

        val EDITOR_BODY = Regex("""<body[^>]*\bid="page-user-edit"""", RegexOption.IGNORE_CASE)
        val EDITOR_FORM = Regex("""<form[^>]*\baction="https://moodle2\.ntust\.edu\.tw/user/edit\.php"""", RegexOption.IGNORE_CASE)
        val NOT_LOGGED_IN_BODY = Regex("""<body[^>]*\bclass="[^"]*\bnotloggedin\b""", RegexOption.IGNORE_CASE)
    }
}

/**
 * Runs [call], a blocking one, apart from its caller on [dispatcher], and hands its answer over
 * only while the caller is still there for it. Cancelled (at a deadline, say), the caller stops
 * waiting at once: a blocking call does not stop for a cancelled coroutine, and one stuck where
 * Call.cancel cannot reach (an address lookup) would hold the caller till it ended. Then
 * [onCancel] cancels what the call is doing, and its answer is never read, even one that came
 * in just as the caller was cancelled: no session taken from a run given up on.
 */
internal suspend fun <T> awaitApart(
    onCancel: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    call: () -> T,
): T {
    currentCoroutineContext().ensureActive()
    val running = CoroutineScope(dispatcher).async { call() }
    return try {
        running.await().also { currentCoroutineContext().ensureActive() }
    } catch (e: CancellationException) {
        onCancel()
        running.cancel()
        throw e
    }
}

/**
 * A sign-in to Moodle's website that found no school SSO session to use: SSO asked for the
 * password, or answered with no bridge back to Moodle. Nothing was posted.
 */
internal class SsoSessionMissing : Exception("SSO holds no session the app can use; no password is posted")
