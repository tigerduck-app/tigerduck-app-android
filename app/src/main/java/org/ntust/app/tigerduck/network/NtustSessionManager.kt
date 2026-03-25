package org.ntust.app.tigerduck.network

import org.ntust.app.tigerduck.data.preferences.AppPreferences
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class LoadingState { IDLE, LOADING, LOADED, ERROR }

@Singleton
class NtustSessionManager @Inject constructor(
    private val prefs: AppPreferences
) {
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            cookieStore.getOrPut(host) { mutableListOf() }.apply {
                removeAll { existing -> cookies.any { it.name == existing.name } }
                addAll(cookies)
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore[url.host] ?: emptyList()
    }

    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("TigerDuck-HTTP", message)
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-TW,zh;q=0.9")
                .build()
            chain.proceed(request)
        }
        .addInterceptor(loggingInterceptor)
        .build()

    companion object {
        private const val COOKIE_TTL_MS = 3600_000L // 1 hour
    }

    val cookiesValid: Boolean
        get() {
            val ts = prefs.ssoLoginTimestamp
            return ts > 0L && System.currentTimeMillis() - ts < COOKIE_TTL_MS
        }

    val loginTimestampMs: Long get() = prefs.ssoLoginTimestamp

    val cookieExpiryMs: Long get() = prefs.ssoLoginTimestamp + COOKIE_TTL_MS

    fun markLoginSuccess() {
        prefs.ssoLoginTimestamp = System.currentTimeMillis()
    }

    fun invalidateSession() {
        cookieStore.clear()
        prefs.clearSsoTimestamp()
    }
}
