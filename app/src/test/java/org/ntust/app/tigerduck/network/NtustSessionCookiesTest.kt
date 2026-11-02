package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.auth.session.AccountSession
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.shared.account.SignInSeries
import org.ntust.app.tigerduck.util.JvmContext

class NtustSessionCookiesTest {

    private val context = JvmContext()
    private val manager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
    private val cour01 = "https://cour01.ntust.edu.tw/classroom_user/classroom_usecondition.aspx".toHttpUrl()
    private val sso = "https://ssoam2.ntust.edu.tw/account/login".toHttpUrl()

    private fun cookie(url: okhttp3.HttpUrl, name: String) =
        Cookie.Builder().name(name).value("v").domain(url.host).path("/").build()

    @Test
    fun `a login that lands once its sign-in has ended leaves no stamp`() {
        val series = SignInSeries()
        val prefs = AppPreferences(context)
        val manager = NtustSessionManager(
            prefs, DemoModeInterceptor(DebugFixtureStore(context)), session = AccountSession(series, CoroutineScope(SupervisorJob())),
        )

        manager.markLoginSuccess()
        assertTrue(prefs.school.ssoLoginTimestamp.get() > 0L)
        prefs.clearSsoTimestamp()
        series.end()
        manager.markLoginSuccess()

        assertEquals("the session is closed: the stamp would date the next account's cookies", 0L, prefs.school.ssoLoginTimestamp.get())
    }

    @Test
    fun `dropping one service's cookies keeps the SSO session`() {
        val jar = manager.client.cookieJar
        jar.saveFromResponse(cour01, listOf(cookie(cour01, "ASP.NET_SessionId")))
        jar.saveFromResponse(sso, listOf(cookie(sso, "AuthServer")))

        manager.dropCookies(cour01.host)

        assertTrue(jar.loadForRequest(cour01).isEmpty())
        assertEquals(listOf("AuthServer"), jar.loadForRequest(sso).map { it.name })
    }
}
