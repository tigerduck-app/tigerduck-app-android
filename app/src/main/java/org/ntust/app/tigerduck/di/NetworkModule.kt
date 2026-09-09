package org.ntust.app.tigerduck.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.network.ApiVersionInterceptor
import org.ntust.app.tigerduck.push.PushIdentity
import org.ntust.app.tigerduck.shared.LibraryService
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
    @Provides
    @Singleton
    fun provideOkHttpClient(
        prefs: AppPreferences,
        demoMode: DemoModeInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
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
    ): LibraryService = LibraryService(
        credentials,
        isDebugBuild = BuildConfig.DEBUG,
        extraInterceptors = listOf(demoMode),
    )

    @Provides
    @Singleton
    fun provideAuthTokenManager(
        credentials: CredentialManager,
        httpClient: OkHttpClient,
        identity: PushIdentity,
        prefs: AppPreferences,
    ): AuthTokenManager = AuthTokenManager(
        credentials = credentials,
        httpClient = httpClient,
        prefs = prefs,
        deviceUuid = identity.uuid(),
    )
}
