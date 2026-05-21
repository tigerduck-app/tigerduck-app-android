package org.ntust.app.tigerduck.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.shared.LibraryService
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

    /**
     * Bridge phone's [CredentialManager] into the shared [LibraryService].
     * The service itself lives in `:shared` (so the watch can use the same
     * wire schema) and takes a [LibraryCredentialStore] interface; binding it
     * to CredentialManager keeps phone storage in EncryptedSharedPreferences.
     */
    @Provides
    @Singleton
    fun provideLibraryService(credentials: CredentialManager): LibraryService =
        LibraryService(credentials, isDebugBuild = BuildConfig.DEBUG)
}
