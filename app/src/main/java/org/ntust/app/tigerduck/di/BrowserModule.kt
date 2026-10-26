package org.ntust.app.tigerduck.di

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import org.ntust.app.tigerduck.browser.AndroidWebViewStorage
import org.ntust.app.tigerduck.browser.BrowserAllowlistStore
import org.ntust.app.tigerduck.browser.BrowserCleanup
import org.ntust.app.tigerduck.browser.BrowserDataCleaner
import org.ntust.app.tigerduck.browser.BrowserDownloads
import org.ntust.app.tigerduck.browser.BrowserSession
import org.ntust.app.tigerduck.browser.BrowserSettings
import org.ntust.app.tigerduck.browser.BrowserSignIns
import org.ntust.app.tigerduck.browser.MediaStoreDownloadTransfer
import org.ntust.app.tigerduck.browser.MoodleBrowserSignIn
import org.ntust.app.tigerduck.browser.MoodleWebSessionSignIn
import org.ntust.app.tigerduck.browser.SchoolBrowserSignIn
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.SsoLoginService
import org.ntust.app.tigerduck.network.moodle.MoodleAutologin
import javax.inject.Singleton
import org.ntust.app.tigerduck.shared.tls.PinnedTls

/** The TigerDuck browser's stores and its sign-in providers. */
@Module
@InstallIn(SingletonComponent::class)
object BrowserModule {

    @Provides
    @Singleton
    fun provideAllowlistStore(@ApplicationContext context: Context): BrowserAllowlistStore = BrowserAllowlistStore.of(context)

    @Provides
    @Singleton
    fun provideBrowserSettings(@ApplicationContext context: Context): BrowserSettings = BrowserSettings(context)

    @Provides
    @Singleton
    fun provideBrowserDownloads(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        allowlist: BrowserAllowlistStore,
        tls: PinnedTls,
    ): BrowserDownloads = BrowserDownloads(
        scope = scope,
        transfer = MediaStoreDownloadTransfer(
            context = context,
            allowlist = { allowlist.allowlist.value },
            cookieFor = { url -> CookieManager.getInstance().getCookie(url) },
            // Synchronous: done inside the download, which a clearing waits for.
            keepCookie = { url, value -> CookieManager.getInstance().setCookie(url, value) },
            userAgent = { runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull() },
            client = MediaStoreDownloadTransfer.client(tls),
        ),
    )

    @Provides
    @Singleton
    fun provideBrowserCleanup(@ApplicationContext context: Context): BrowserCleanup = BrowserCleanup.of(context)

    @Provides
    @Singleton
    fun provideBrowserDataCleaner(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        settings: BrowserSettings,
        downloads: BrowserDownloads,
        cleanup: BrowserCleanup,
    ): BrowserDataCleaner = BrowserDataCleaner(AndroidWebViewStorage(context), settings, downloads, cleanup, scope)

    /**
     * Who signs the browser in where: Moodle, through its autologin, and between
     * its keys through the app's own sign-in to its website with the SSO session
     * the app holds. A school SSO provider would join this list; none is written yet.
     */
    @Provides
    @Singleton
    fun provideBrowserSignIns(
        @ApplicationContext context: Context,
        autologin: MoodleAutologin,
        tokenService: MoodleTokenService,
        ssoLoginService: SsoLoginService,
        credentials: org.ntust.app.tigerduck.data.preferences.CredentialManager,
        @ApplicationScope appScope: CoroutineScope,
        demoAccount: DemoAccount,
        cleanup: BrowserCleanup,
    ): BrowserSignIns {
        val usable = { BrowserSession.usable && !BrowserSession.clearing.value && !cleanup.isPending() }
        val webSession = MoodleWebSessionSignIn(
            run = tokenService::signInToWebsite,
            isDemo = { demoAccount.isActive },
            usable = usable,
            generation = { BrowserSession.generation.value },
            writeCookie = { url, value -> CookieManager.getInstance().setCookie(url, value) },
            flush = { CookieManager.getInstance().flush() },
        )
        val school = SchoolBrowserSignIn(
            run = ssoLoginService::signInForBrowser,
            // The app's own sign-in, guarded as ever (one at a time, a refused password held back),
            // by the way of the site: it signs in to SSO again whatever the app thinks of its
            // session. In the app's scope, awaited: a timeout leaves it to finish for the app.
            warm = { target ->
                appScope.async {
                    val id = credentials.ntustStudentId ?: return@async false
                    val password = credentials.ntustPassword ?: return@async false
                    try {
                        ssoLoginService.ensureServiceLogin(target, id.trim().uppercase(), password) {
                            // Not for an account that signed out while it waited.
                            credentials.ntustStudentId == id
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }.await()
            },
            isDemo = { demoAccount.isActive },
            usable = usable,
            generation = { BrowserSession.generation.value },
            writeCookie = { url, value -> CookieManager.getInstance().setCookie(url, value) },
            flush = { CookieManager.getInstance().flush() },
        )
        // Moodle's first: its site is the school's too.
        return BrowserSignIns(listOf(MoodleBrowserSignIn(autologin, webSession), school))
    }
}
