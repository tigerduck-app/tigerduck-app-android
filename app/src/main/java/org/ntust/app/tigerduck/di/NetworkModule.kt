package org.ntust.app.tigerduck.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.network.tls.PinManager
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PushIdentity
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.shared.tls.PinnedTls
import org.ntust.app.tigerduck.shared.tls.pinnedClientBuilder
import org.ntust.app.tigerduck.ui.component.ServerFailureInterceptor
import org.ntust.app.tigerduck.ui.component.ServerKind
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * The [ApiVersionInterceptor] sits on the shared client so every call to
     * our backend is seen, whichever service made it. It filters by host, so
     * the NTUST / Moodle / library traffic that shares this client is
     * unaffected.
     */
    /**
     * The trust every HTTPS client of the app's uses, the library's and School Mail's among
     * them: the platform's system trust with [PinManager]'s pins on top (TLS pins spec, A9).
     */
    @Provides
    @Singleton
    fun providePinnedTls(pins: PinManager): PinnedTls = PinnedTls.forPlatform(pins.state, pins::onMismatch, pins)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        prefs: AppPreferences,
        demoMode: DemoModeInterceptor,
        tls: PinnedTls,
    ): OkHttpClient =
        pinnedClientBuilder(tls)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // First, so a demo session refuses the call before any other
            // interceptor gets to stamp headers on a request that is not
            // going anywhere.
            .addInterceptor(demoMode)
            .addInterceptor(ApiVersionInterceptor(prefs))
            .build()

    /**
     * Bridge phone's [CredentialManager] into the shared [LibraryService].
     * The service itself lives in `:shared` (so the watch can use the same
     * wire schema) and takes a [LibraryCredentialStore] interface; binding it
     * to CredentialManager keeps phone storage in EncryptedSharedPreferences.
     */
    @Provides
    @Singleton
    fun provideLibraryService(
        credentials: CredentialManager,
        demoMode: DemoModeInterceptor,
        tls: PinnedTls,
    ): LibraryService = LibraryService(
        credentials,
        isDebugBuild = BuildConfig.DEBUG,
        extraInterceptors = libraryInterceptors(demoMode, debug = BuildConfig.DEBUG),
        tls = tls,
    )

    /**
     * The library client's own interceptors: the demo-mode kill switch, and in a debug build the
     * server failure simulator's Library row, which fails the library's sign-in and code the way
     * the other rows fail theirs.
     */
    internal fun libraryInterceptors(demoMode: Interceptor, debug: Boolean): List<Interceptor> =
        listOfNotNull(demoMode, if (debug) ServerFailureInterceptor(ServerKind.LIBRARY) else null)

    @Provides
    @Singleton
    fun provideAuthTokenManager(
        credentials: CredentialManager,
        httpClient: OkHttpClient,
        identity: PushIdentity,
        prefs: AppPreferences,
        owedUnregister: OwedUnregister,
    ): AuthTokenManager = AuthTokenManager(
        tokens = credentials.v3Tokens,
        httpClient = httpClient,
        prefs = prefs,
        deviceUuid = identity.uuid(),
        owedUnregister = owedUnregister,
    )
}
