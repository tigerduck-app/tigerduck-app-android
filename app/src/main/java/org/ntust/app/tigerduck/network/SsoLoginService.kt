package org.ntust.app.tigerduck.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.auth.postAutomatically
import org.ntust.app.tigerduck.shared.CredentialPost
import org.ntust.app.tigerduck.shared.executeCredentialPost
import javax.inject.Inject
import javax.inject.Singleton

sealed class SsoLoginError : Exception() {
    class LoginFormNotFound : SsoLoginError()

    /** The school kept the sign-in on its form. [reason] is its message, when the page had one. */
    class LoginFailed(val reason: String? = null) : SsoLoginError()
    class InvalidResponse : SsoLoginError()
    data class NetworkError(val cause_: Exception) : SsoLoginError()

    /**
     * An automatic sign-in's credentials stopped being the stored account's while it ran, as a
     * sign-in by hand stored another password or the account signed out, so it posted nothing.
     */
    class AccountChanged : SsoLoginError()

    /** The service bounced to a login page OkHttp cannot complete (ssoam's NetIQ form, gated
     *  by a click-only reCAPTCHA). No session was established — only a WebView can finish it. */
    class InteractiveLoginRequired : SsoLoginError()

    /** SSO asked for the password where a sign-in for the browser posts none: the app holds no SSO session it can use. */
    class SessionMissing : SsoLoginError()
}

/**
 * A page behind NTUST SSO, fetched with the session already held when
 * [sessionWarm] says there is one, and with one login and one retry when the
 * request is bounced to SSO anyway.
 *
 * [fetch] returns null for a bounce. [logIn] throws when it cannot log in.
 * Whatever the retry still cannot get past ends in [bounced], as does a
 * bounce after a cold session's login.
 *
 * A login costs a round of the OIDC bridge — the service root, the authorize
 * redirect, the bridge POST — even when the session it checks is fine, so
 * running one ahead of every fetch cost several requests where a warm
 * session needs one.
 * The retry is what makes skipping it safe: [sessionWarm] only says *a*
 * login succeeded within the hour, not that it was to this service.
 */
internal suspend fun <T> fetchWithSsoSession(
    sessionWarm: Boolean,
    logIn: suspend () -> Unit,
    fetch: suspend () -> T?,
    bounced: () -> Nothing,
): T {
    if (!sessionWarm) {
        // A bounce straight after a login of our own is not a stale session,
        // and a second login would only clear the cookies and do it again.
        logIn()
        return fetch() ?: bounced()
    }
    fetch()?.let { return it }
    logIn()
    return fetch() ?: bounced()
}

/**
 * [block] under the lock, unless [isStillWanted] says, once the lock is held, that whoever asked
 * no longer wants it; then [stale] without running it.
 */
internal suspend fun <T> Mutex.lockedUnlessStale(isStillWanted: () -> Boolean, stale: T, block: suspend () -> T): T =
    withLock { if (isStillWanted()) block() else stale }

@Singleton
class SsoLoginService internal constructor(
    private val sessionManager: NtustSessionManager,
    private val passwordLatch: StoredPasswordLatch,
    /** A jar for one sign-in for the TigerDuck browser, its own; see [signInForBrowser]. */
    private val newCookieJar: () -> SignInCookieJar = { SignInCookieJar() },
    private val clientFor: () -> OkHttpClient,
) {
    @Inject
    constructor(sessionManager: NtustSessionManager, passwordLatch: StoredPasswordLatch) :
        this(sessionManager, passwordLatch, clientFor = { sessionManager.client })

    private val client: OkHttpClient get() = clientFor()

    // One login at a time. A login that meets the SSO wall clears the whole
    // cookie jar (step 4) before it signs in, which pulled the session out
    // from under any other login running alongside — and Home, the class
    // table and the calendar each started one at the same moment on launch.
    private val loginMutex = Mutex()

    /**
     * Ensures the user is logged in to the given service via NTUST SSO, with
     * the stored account. Returns true on success, throws SsoLoginError on
     * failure. While a refusal of the stored password holds its next post
     * back, it posts none and throws [SsoLoginError.LoginFailed] — see
     * [StoredPasswordLatch].
     */
    suspend fun ensureServiceLogin(
        serviceUrl: String,
        studentId: String,
        password: String
    ): Boolean = loginMutex.withLock { logIn(serviceUrl, studentId, password) }

    /**
     * [ensureServiceLogin] for a caller whose account may sign out while it waits for the lock:
     * [isStillWanted] is asked once the lock is held, and a login no longer wanted returns false
     * without a request, rather than signing a departed account in again.
     */
    suspend fun ensureServiceLogin(
        serviceUrl: String,
        studentId: String,
        password: String,
        isStillWanted: () -> Boolean,
    ): Boolean = loginMutex.lockedUnlessStale(isStillWanted, stale = false) {
        logIn(serviceUrl, studentId, password)
    }

    /**
     * [ensureServiceLogin] past the session the jar holds, for the retry of a refused stored
     * password: a session still live lets [ensureServiceLogin] through without a post, which says
     * nothing about the password, so the jar is emptied first, under the same lock. A refusal
     * the school answers is counted; its yes ends nothing yet, and goes to [accepted] instead, as
     * the retry settles the count itself once the Moodle token step is through too
     * ([org.ntust.app.tigerduck.auth.RetrySettlement]). A sign-in that needed no post calls it
     * not at all.
     *
     * [stillRefused] is asked once the lock is held: an automatic sign-in that held it first, and
     * that the school took, settles the refusal, and this then keeps its session and posts nothing.
     * A refusal's wait still holds it back, checked before the jar is emptied: another automatic
     * post refused while this one waited for the lock starts a new wait, and a session no post
     * could replace stays as it is.
     */
    suspend fun signInAgain(
        serviceUrl: String,
        studentId: String,
        password: String,
        accepted: () -> Unit = {},
        stillRefused: () -> Boolean,
    ): Boolean =
        loginMutex.withLock {
            if (!stillRefused()) return@withLock true
            if (passwordLatch.postMustWait()) throw SsoLoginError.LoginFailed(passwordLatch.rejection.value?.reason)
            sessionManager.invalidateSession()
            logIn(serviceUrl, studentId, password, accepted = accepted)
        }

    /**
     * A sign-in with credentials just entered. [ensureServiceLogin] takes a
     * session the jar already holds as signed in, without submitting the
     * credentials; that is right for the account re-logging itself in, and
     * wrong for someone new, whose sign-in would then pass on the strength
     * of a session left by the account before. So this one empties the jar
     * first. Under the same lock, so a login still running for the account
     * that left finishes before the jar is emptied, not after.
     *
     * It posts the credentials whatever the latch holds, and leaves the latch
     * as it is: the refused password is still the one stored until the
     * sign-in stores the new one, which lifts it (TypedAccountSignIn).
     */
    suspend fun signIn(
        serviceUrl: String,
        studentId: String,
        password: String
    ): Boolean = loginMutex.withLock {
        sessionManager.invalidateSession()
        logIn(serviceUrl, studentId, password, typed = true)
    }

    /**
     * [typed] for credentials the user just entered. Any other login is one
     * the user did not start, and posts no password the school refused.
     * The school taking an automatic post ends the refusals, or with
     * [accepted] goes to it instead: the retry of a refused password settles
     * them itself.
     */
    private suspend fun logIn(
        serviceUrl: String,
        studentId: String,
        password: String,
        typed: Boolean = false,
        accepted: (() -> Unit)? = null,
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Before anything that could be refused, so a refusal that lands after
        // the user signed in again or signed out latches nothing.
        val latchGeneration = passwordLatch.generation

        // Step 1: Visit service URL
        var (html, url) = fetchPage(serviceUrl)

        // Step 2: Resolve OIDC bridge forms
        val resolved = resolveOIDCBridgeForms(html, url)
        html = resolved.first
        url = resolved.second

        // Step 3: Check if we're on SSO login page
        if (!HtmlParser.isSSOLoginPage(html, url)) {
            sessionManager.markLoginSuccess()
            return@withContext true
        }

        // The session needs the password now, and each refusal of it counts
        // toward the account's lockout, so a refusal's wait holds it back.
        // Before step 4, which would empty the jar for a sign-in that is not
        // going to happen.
        if (!typed && passwordLatch.postMustWait()) {
            throw SsoLoginError.LoginFailed(passwordLatch.rejection.value?.reason)
        }

        // Step 4: Clear cookies and retry
        sessionManager.invalidateSession()

        val fresh = fetchPage(serviceUrl)
        html = fresh.first
        url = fresh.second

        if (!HtmlParser.isSSOLoginPage(html, url)) {
            // Capture the bridge result instead of discarding it: a bridge
            // POST that lands back on the SSO wall means we aren't actually
            // logged in, so we must not mark success — fall through to the
            // credential submission below.
            val (bridgeHtml, bridgeUrl) = resolveOIDCBridgeForms(html, url)
            if (!HtmlParser.isSSOLoginPage(bridgeHtml, bridgeUrl)) {
                sessionManager.markLoginSuccess()
                return@withContext true
            }
            html = bridgeHtml
            url = bridgeUrl
        }

        // Step 5: Submit login form
        val form = HtmlParser.findFormById(html, "loginForm") ?: run {
            // ssoam's NetIQ Access Manager page (`id="IDPLogin"`) binds its submit control to an
            // invisible reCAPTCHA that only a person's click passes, so no password goes there
            // and nothing was authenticated: this must not read as success, as sign-in and
            // scrapers need a real session. In the TigerDuck browser the student signs in there
            // by hand.
            if (HtmlParser.findFormById(html, "IDPLogin") != null) {
                throw SsoLoginError.InteractiveLoginRequired()
            }
            throw SsoLoginError.LoginFormNotFound()
        }

        val fields = form.inputs.toMutableList().apply {
            replaceOrAppend("Username", studentId)
            replaceOrAppend("Password", password)
            if (none { it.first == "captcha" }) add("captcha" to "")
        }

        val actionUrl = resolveUrl(form.action, url)
        // The credentials go to the school's sign-in host and nowhere else, whatever the page
        // names as the form's action. As on the iPhone (ssoActionHostAllowlist).
        if (!isSignInHost(actionUrl)) throw SsoLoginError.InvalidResponse()
        if (typed) return@withContext submitLoginForm(actionUrl, fields)
        // Under the lock every automatic post of the stored password takes, with the refusals and
        // the stored account checked again right before the password goes out: a refusal
        // another sign-in got, the Moodle token renewal's included, has landed by then, and
        // none lands while this one is posting. The school taking it ends the refusals; the
        // returns above, which a live session let through without a post, leave them.
        passwordLatch.postAutomatically(
            generation = latchGeneration,
            studentId = studentId,
            password = password,
            refused = { SsoLoginError.LoginFailed(it.reason) },
            stale = { SsoLoginError.AccountChanged() },
        ) {
            try {
                submitLoginForm(actionUrl, fields).also { signedIn ->
                    if (!signedIn) return@also
                    if (accepted != null) accepted() else passwordLatch.markAccepted(latchGeneration, studentId, password)
                }
            } catch (e: SsoLoginError.LoginFailed) {
                passwordLatch.markRejected(latchGeneration, studentId, password, e.reason)
                throw e
            }
        }
    }

    /**
     * Signs [target]'s school site in for the TigerDuck browser with the SSO session the app
     * already holds, and hands back the cookies the site set: its session, for the browser alone.
     * The run has a jar of its own ([newCookieJar]) that starts with what the app's shared jar
     * sends SSO and nothing else, and nothing goes back into the shared jar, so the site's session
     * is never one the app's own calls also use. Nothing the run started with is handed back.
     *
     * No password is posted, and none is filled into a page: SSO asking for one ends the run
     * ([SsoLoginError.SessionMissing]), and so does ssoam's NetIQ page, which only a person signs
     * in to ([SsoLoginError.InteractiveLoginRequired]). A page that asks for no sign-in hands back
     * whatever its site set.
     */
    suspend fun signInForBrowser(target: String): List<Cookie> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val jar = newCookieJar()
        val seeded = client.cookieJar.loadForRequest(SSO_SITE)
        jar.saveFromResponse(SSO_SITE, seeded)
        val run = client.newBuilder().cookieJar(jar).build()
        val (html, url) = fetchPage(target, run)
        val (landedHtml, landed) = resolveOIDCBridgeForms(html, url, client = run)
        if (HtmlParser.findFormById(landedHtml, "IDPLogin") != null) throw SsoLoginError.InteractiveLoginRequired()
        if (HtmlParser.isSsoHost(landed.host)) throw SsoLoginError.SessionMissing()
        val started = seeded.map { Triple(it.name, it.domain, it.path) }.toSet()
        jar.all().filter { Triple(it.name, it.domain, it.path) !in started && !HtmlParser.isSsoHost(it.domain) }
    }

    /**
     * Steps 5 to 7: posts the sign-in form's [fields] to [actionUrl] and follows the answer. Throws
     * [SsoLoginError.LoginFailed] with the school's message when the school kept the sign-in on its
     * form, and for nothing else.
     */
    private fun submitLoginForm(actionUrl: HttpUrl, fields: List<Pair<String, String>>): Boolean {
        var (html, url) = postLoginForm(actionUrl, fields)

        // Step 6: Resolve OIDC bridge forms after login
        val afterLogin = resolveOIDCBridgeForms(html, url)
        html = afterLogin.first
        url = afterLogin.second

        // Step 7: Check if still on SSO page
        if (HtmlParser.isSSOLoginPage(html, url)) {
            throw SsoLoginError.LoginFailed(HtmlParser.loginErrorMessage(html))
        }

        sessionManager.markLoginSuccess()
        return true
    }

    private fun fetchPage(url: String, client: OkHttpClient = this.client): Pair<String, HttpUrl> {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                return body to (response.request.url)
            }
        } catch (e: SsoLoginError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SsoLoginError.NetworkError(e)
        }
    }

    private fun resolveOIDCBridgeForms(
        html: String,
        url: HttpUrl,
        maxSteps: Int = 3,
        client: OkHttpClient = this.client,
    ): Pair<String, HttpUrl> {
        var currentHtml = html
        var currentUrl = url
        repeat(maxSteps) {
            if (HtmlParser.isSSOLoginPage(currentHtml, currentUrl)) return currentHtml to currentUrl
            val form =
                HtmlParser.findOIDCBridgeForm(currentHtml) ?: return currentHtml to currentUrl
            val actionUrl = resolveUrl(form.action, currentUrl)
            // What the bridge carries passes the sign-in on, so it goes to the school's hosts over
            // HTTPS and nowhere else, whatever the page names as the form's action. As on the
            // iPhone.
            if (!CredentialPost.isSchoolTarget(actionUrl)) throw SsoLoginError.InvalidResponse()
            val (newHtml, newUrl) = postForm(actionUrl, form.inputs, client)
            currentHtml = newHtml
            currentUrl = newUrl
        }
        return currentHtml to currentUrl
    }

    /**
     * The sign-in form's POST, which carries the password: no redirect sends the form on to
     * another address, and one that would ends the sign-in ([executeSignInFormPost]).
     */
    private fun postLoginForm(url: HttpUrl, fields: List<Pair<String, String>>): Pair<String, HttpUrl> {
        try {
            val body = FormBody.Builder().apply {
                fields.forEach { (name, value) -> add(name, value) }
            }.build()
            val request = Request.Builder().url(url).post(body).build()
            val response = client.executeSignInFormPost(request) ?: throw SsoLoginError.InvalidResponse()
            response.use { return it.body.string() to it.request.url }
        } catch (e: SsoLoginError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SsoLoginError.NetworkError(e)
        }
    }

    /**
     * A bridge form's POST, which carries the ID token or the code the school hands the service: no
     * redirect sends the form on, and one that would ends the sign-in ([executeCredentialPost]).
     */
    private fun postForm(url: HttpUrl, fields: List<Pair<String, String>>, client: OkHttpClient = this.client): Pair<String, HttpUrl> {
        try {
            val body = FormBody.Builder().apply {
                fields.forEach { (name, value) -> add(name, value) }
            }.build()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val response = client.executeCredentialPost(request) ?: throw SsoLoginError.InvalidResponse()
            response.use {
                val html = it.body.string()
                return html to it.request.url
            }
        } catch (e: SsoLoginError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SsoLoginError.NetworkError(e)
        }
    }

    private fun resolveUrl(path: String, base: HttpUrl): HttpUrl {
        return try {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                path.toHttpUrl()
            } else {
                base.newBuilder(path)?.build() ?: base
            }
        } catch (e: IllegalArgumentException) {
            base
        }
    }

    private fun MutableList<Pair<String, String>>.replaceOrAppend(name: String, value: String) {
        val idx = indexOfFirst { it.first == name }
        if (idx >= 0) this[idx] = name to value
        else add(name to value)
    }
}

/** Where the sign-in form may post the credentials: the school's sign-in host, over HTTPS. */
internal fun isSignInHost(url: HttpUrl): Boolean = url.isHttps && url.host == SIGN_IN_HOST

private const val SIGN_IN_HOST = "ssoam2.ntust.edu.tw"

/** The sign-in host's site, whose cookies the app's shared jar sends SSO. */
private val SSO_SITE: HttpUrl = "https://$SIGN_IN_HOST/".toHttpUrl()
