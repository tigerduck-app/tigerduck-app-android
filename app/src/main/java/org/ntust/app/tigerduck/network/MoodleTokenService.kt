package org.ntust.app.tigerduck.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import java.net.SocketTimeoutException
import java.security.SecureRandom
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
class MoodleTokenService @Inject constructor(
    private val sessionManager: NtustSessionManager,
    private val credentialManager: CredentialManager,
) {
    private val mutex = Mutex()
    private val moodleBaseUrl = "https://moodle2.ntust.edu.tw".toHttpUrl()

    private val moodleUa =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 " +
            "MoodleMobile 5.1.1 (51100)"

    /**
     * Dedicated client that forces the Moodle Mobile UA on every hop of the
     * OIDC chain (SSO portal + Moodle). The session manager's default
     * interceptor only swaps UA for moodle2.* — SSO hops need it too so
     * NetScaler doesn't serve an anti-bot challenge mid-redirect.
     */
    private val tokenClient: OkHttpClient by lazy {
        sessionManager.client.newBuilder()
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

    fun currentToken(): String? = credentialManager.moodleToken

    suspend fun clearToken() {
        credentialManager.moodleToken = null
    }

    /** Fetch a fresh token using stored NTUST credentials. */
    suspend fun refreshToken(): String = mutex.withLock {
        val sid = credentialManager.ntustStudentId
            ?: throw MoodleWebserviceError.MissingStoredCredentials
        val pwd = credentialManager.ntustPassword
            ?: throw MoodleWebserviceError.MissingStoredCredentials
        val triple = performOidcLogin(sid.trim().uppercase(), pwd)
        credentialManager.moodleToken = triple.wstoken
        Log.i("MoodleTokenService", "refreshed wstoken (len=${triple.wstoken.length})")
        triple.wstoken
    }

    /** Fetch a fresh token using the supplied credentials (first-time login). */
    suspend fun obtainToken(studentId: String, password: String): String = mutex.withLock {
        val triple = performOidcLogin(studentId.trim().uppercase(), password)
        credentialManager.moodleToken = triple.wstoken
        Log.i("MoodleTokenService", "obtained wstoken (len=${triple.wstoken.length})")
        triple.wstoken
    }

    private data class TokenTriple(
        val signature: String,
        val wstoken: String,
        val privatetoken: String?,
    )

    private suspend fun performOidcLogin(
        studentId: String,
        password: String,
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
        val (html, finalUrl) = getPage(launchUrl)
        resolveTokenTriple(
            html = html,
            responseUrl = finalUrl,
            moodleBaseUrl = moodleBaseUrl,
            studentId = studentId,
            password = password,
            passport = passport,
            remainingSteps = 6,
        )
    }

    private suspend fun resolveTokenTriple(
        html: String,
        responseUrl: HttpUrl,
        moodleBaseUrl: HttpUrl,
        studentId: String,
        password: String,
        passport: String,
        remainingSteps: Int,
    ): TokenTriple {
        if (remainingSteps <= 0) {
            throw MoodleWebserviceError.MalformedResponse("OIDC flow exceeded maximum step count")
        }

        // Terminal state: the mobile launch confirmation page embeds a
        // custom-scheme URL `moodlemobile://token=BASE64`. That's our token.
        extractMoodleMobileToken(html)?.let { base64 ->
            return decodeTokenTriple(base64)
        }

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
            Log.i("MoodleTokenService", "posting OIDC bridge → ${url.redactForLog()}")
            val (nextHtml, nextUrl) = postForm(url, bridge.payload, referer = responseUrl.toString())
            return resolveTokenTriple(
                html = nextHtml,
                responseUrl = nextUrl,
                moodleBaseUrl = moodleBaseUrl,
                studentId = studentId,
                password = password,
                passport = passport,
                remainingSteps = remainingSteps - 1,
            )
        }

        // SSO login step: we landed on ssoam2.ntust.edu.tw with a form.
        if (responseUrl.host.contains("ssoam2.ntust.edu.tw")) {
            val fields = parseSSOLoginFields(html)
            if (fields.antiforgery.isEmpty()) {
                if (responseUrl.encodedPath.contains("/account/login")) {
                    throw MoodleWebserviceError.SsoLoginRejected(extractLoginError(html))
                }
                throw MoodleWebserviceError.MalformedResponse("SSO login form missing anti-forgery token")
            }
            val postUrl = responseUrl.resolve(fields.action)
                ?: throw MoodleWebserviceError.MalformedResponse("Invalid SSO action: ${fields.action}")
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
            Log.i("MoodleTokenService", "posting SSO login → ${postUrl.redactForLog()}")
            val (nextHtml, nextUrl) = postForm(
                url = postUrl,
                fields = payload,
                referer = responseUrl.toString(),
                origin = "https://ssoam2.ntust.edu.tw",
            )

            // If we're still at /account/login and neither a token nor an OIDC
            // bridge is in sight, the credentials were rejected.
            if (nextUrl.encodedPath.contains("/account/login") &&
                extractMoodleMobileToken(nextHtml) == null &&
                parseOIDCBridge(nextHtml) == null
            ) {
                throw MoodleWebserviceError.SsoLoginRejected(extractLoginError(nextHtml))
            }
            return resolveTokenTriple(
                html = nextHtml,
                responseUrl = nextUrl,
                moodleBaseUrl = moodleBaseUrl,
                studentId = studentId,
                password = password,
                passport = passport,
                remainingSteps = remainingSteps - 1,
            )
        }

        // Redact secrets before they hit logs/crash reports: the URL goes
        // through `redactForLog()` (masks passport/token/code/state query
        // params) and the HTML preview gets any input value of length ≥8
        // collapsed to `value="***"` to scrub OIDC `code`/`state`/session
        // values that ride hidden form fields between hops.
        val safePreview = html.take(300)
            .replace(Regex("value=\"[^\"]{8,}\""), "value=\"***\"")
            .replace("\n", " ")
        throw MoodleWebserviceError.MalformedResponse(
            "Unexpected OIDC page: ${responseUrl.redactForLog()} — body preview=$safePreview"
        )
    }

    // MARK: - HTTP helpers

    private fun getPage(url: HttpUrl): Pair<String, HttpUrl> {
        val req = Request.Builder().url(url).get().build()
        return executeAndReadBody(req)
    }

    private fun postForm(
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
        return executeAndReadBody(reqBuilder.build())
    }

    private fun executeAndReadBody(request: Request): Pair<String, HttpUrl> {
        val response = try {
            tokenClient.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            throw MoodleWebserviceError.TransientNetwork("timeout: ${e.message}")
        } catch (e: java.io.IOException) {
            throw MoodleWebserviceError.TransientNetwork(e.message ?: "io error")
        }
        response.use { r ->
            if (!r.isSuccessful) {
                throw MoodleWebserviceError.HttpStatus(r.code)
            }
            val body = r.body?.string()
                ?: throw MoodleWebserviceError.MalformedResponse("empty response body")
            return body to r.request.url
        }
    }

    // MARK: - HTML parsing

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

    private fun extractLoginError(html: String): String? {
        val classes = listOf(
            "field-validation-error",
            "validation-summary-errors",
            "alert-danger",
            "text-danger",
        )
        for (cls in classes) {
            val re = Regex(
                """<[^>]*class=["'][^"']*\b$cls\b[^"']*["'][^>]*>([\s\S]*?)</[^>]+>""",
                RegexOption.IGNORE_CASE,
            )
            val match = re.find(html) ?: continue
            val fragment = match.groupValues[1]
            val text = fragment
                .replace(Regex("<[^>]+>"), " ")
                .let(::decodeHtmlEntities)
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

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
}
