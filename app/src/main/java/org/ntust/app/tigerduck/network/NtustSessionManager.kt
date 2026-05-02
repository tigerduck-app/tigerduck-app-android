package org.ntust.app.tigerduck.network

import org.ntust.app.tigerduck.data.preferences.AppPreferences
import android.util.Log
import org.ntust.app.tigerduck.BuildConfig
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class LoadingState { IDLE, LOADING, LOADED, ERROR }

@Singleton
class NtustSessionManager @Inject constructor(
    private val prefs: AppPreferences
) {
    private val cookieStore = ConcurrentHashMap<String, CopyOnWriteArrayList<Cookie>>()

    init {
        // Clear stale SSO timestamp if cookie store is empty after process restart
        if (cookieStore.isEmpty() && prefs.ssoLoginTimestamp > 0L) {
            prefs.clearSsoTimestamp()
        }
    }

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            // computeIfAbsent is atomic on ConcurrentHashMap; getOrPut isn't,
            // so two concurrent callers could each create their own list and
            // lock on different instances for the same host.
            val hostCookies = cookieStore.computeIfAbsent(host) { CopyOnWriteArrayList() }
            synchronized(hostCookies) {
                hostCookies.removeAll { existing -> cookies.any { it.name == existing.name } }
                hostCookies.addAll(cookies)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host]?.toList() ?: emptyList()
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("TigerDuck-HTTP", redactSensitive(message))
    }.apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
                else HttpLoggingInterceptor.Level.NONE
        // Session cookies and wstoken can appear in these headers; mask them
        // even in debug so logcat doesn't carry long-lived credentials.
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
        redactHeader("Authorization")
    }

    // Default OkHttp caps parallelism at 5 requests per host, which
    // serialized the tail of lookupCourse (typical 7–10 enrolled courses)
    // and get_submission_status fan-outs on first load. Raise the ceiling so
    // all concurrent course/assignment calls fire at once.
    private val sharedDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 20
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .dispatcher(sharedDispatcher)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            // Pose as the Moodle Mobile App for any moodle2.ntust.edu.tw
            // request — the NTUST edge (Citrix NetScaler) serves an
            // anti-bot JS challenge page to generic Android/Chrome UAs
            // when hitting /my/ directly, which breaks our sesskey scrape.
            // The Moodle app UA is on the allow-list and gets through.
            val ua = if (req.url.host.endsWith("moodle2.ntust.edu.tw")) {
                "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 " +
                    "MoodleMobile 5.1.1 (51100)"
            } else {
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            // Respect caller-set Accept. querycourse.ntust.edu.tw does strict
            // content negotiation on Accept and returns HTTP 500 with an XML
            // error body when the header prefers text/html over JSON, which
            // silently broke lookupCourse/searchCourses after we retired the
            // plain OkHttpClient that had no Accept header at all.
            val acceptHeader = req.header("Accept")
                ?: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            val request = req.newBuilder()
                .header("User-Agent", ua)
                .header("Accept", acceptHeader)
                .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            chain.proceed(request)
        }
        .addInterceptor(loggingInterceptor)
        .build()

    companion object {
        private const val COOKIE_TTL_MS = 3600_000L // 1 hour

        private val SENSITIVE_PARAM_REGEX =
            Regex("""\b(wstoken|passport|token|code|state|password|Password)=([^&\s"]+)""")

        fun redactSensitive(message: String): String =
            SENSITIVE_PARAM_REGEX.replace(message) { m -> "${m.groupValues[1]}=***" }
    }

    val cookiesValid: Boolean
        get() {
            val ts = prefs.ssoLoginTimestamp
            return ts > 0L && System.currentTimeMillis() - ts < COOKIE_TTL_MS && cookieStore.isNotEmpty()
        }

    val loginTimestampMs: Long get() = prefs.ssoLoginTimestamp

    val cookieExpiryMs: Long get() = prefs.ssoLoginTimestamp + COOKIE_TTL_MS

    fun markLoginSuccess() {
        prefs.ssoLoginTimestamp = System.currentTimeMillis()
    }

    fun invalidateSession() {
        // Clear the timestamp first so a concurrent cookiesValid read can't
        // observe ts > 0 AND cookies present after the clear. Reordering this
        // produces a tear where logout races with a refresh and cookiesValid
        // returns true while the session is being torn down.
        prefs.clearSsoTimestamp()
        cookieStore.clear()
    }
}
